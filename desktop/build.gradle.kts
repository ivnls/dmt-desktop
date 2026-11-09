import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

val appVersion = (findProperty("versionOverride") as String?) ?: "1.4.0"

// Which OS/arch the natives (skia + ffmpeg) are bundled for. Defaults to the host.
// Override with -Ptmpl.target=linux-x64 | linux-arm64 | windows-x64
val hostTarget: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val a = if (arch == "aarch64" || arch == "arm64") "arm64" else "x64"
    when {
        os.contains("win") -> "windows-$a"
        os.contains("mac") -> "macos-$a"
        else -> "linux-$a"
    }
}
val tmplTarget: String = (findProperty("tmpl.target") as String?) ?: hostTarget

val bytedecoClassifier = when (tmplTarget) {
    "linux-x64" -> "linux-x86_64"
    "linux-arm64" -> "linux-arm64"
    "windows-x64" -> "windows-x86_64"
    "macos-x64" -> "macosx-x86_64"
    "macos-arm64" -> "macosx-arm64"
    else -> error("unknown tmpl.target $tmplTarget")
}
val skikoTarget = "org.jetbrains.compose.desktop:desktop-jvm-$tmplTarget:${libs.versions.compose.get()}"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
    }
}

dependencies {
    implementation(skikoTarget)
    implementation(libs.compose.material3)
    implementation(libs.compose.resources)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(project(":metadata"))
    implementation(project(":lyrics"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.json)
    implementation(libs.dbus.core)
    implementation(libs.dbus.unixsocket)
    implementation(libs.jna.platform)

    // ffmpeg (decoding + resampling + atempo) through javacv / javacpp
    implementation(libs.javacv) {
        isTransitive = false
    }
    implementation(libs.javacpp)
    implementation(libs.ffmpeg)
    runtimeOnly(variantOf(libs.javacpp) { classifier(bytedecoClassifier) })
    runtimeOnly(variantOf(libs.ffmpeg) { classifier(bytedecoClassifier) })

    testImplementation(libs.junit)
}

compose.resources {
    publicResClass = false
    packageOfResClass = "dev.jyotiraditya.dmt.resources"
    generateResClass = always
}

compose.desktop {
    application {
        mainClass = "dev.jyotiraditya.dmt.MainKt"
        // The JDK whose runtime gets bundled. Use a portable build (e.g. Eclipse Temurin) for
        // release packages: distro JDKs can be linked against a newer glibc than older distros
        // ship (ubuntu 24.04's openjdk needs glibc 2.38, debian 12 has 2.36).
        ((findProperty("tmpl.javaHome") as String?) ?: System.getenv("TMPL_JAVA_HOME"))?.let { javaHome = it }
        jvmArgs += listOf("-Xss2m", "--enable-native-access=ALL-UNNAMED")
        // lets Main set the X11 WM_CLASS to "tmpl" so docks match the window to tmpl.desktop
        if (tmplTarget.startsWith("linux")) jvmArgs += "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED"

        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "tmpl"
            packageVersion = appVersion
            description = "tmpl - tui-inspired music player, based on dmt by Jyotiraditya Panda"
            vendor = "ivnls"
            copyright = "GPL-3.0 - ivnls, based on dmt (c) Jyotiraditya Panda"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.desktop", "java.logging", "java.naming", "java.instrument", "java.management", "jdk.security.auth", "jdk.unsupported", "jdk.crypto.ec", "java.sql")

            linux {
                iconFile.set(project.file("icons/tmpl.png"))
                packageName = "tmpl"
                debMaintainer = "ivnls@users.noreply.github.com"
                menuGroup = "AudioVideo;Audio;Player"
                appCategory = "sound"
                appRelease = "1"
                rpmLicenseType = "GPL-3.0"
                shortcut = true
            }
            windows {
                iconFile.set(project.file("icons/tmpl.ico"))
                menuGroup = "tmpl"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "8a6f2a3c-6a4f-4f63-9d38-2f8a1e5f0d71"
            }
        }
    }
}

// Fat jar for any target (works cross-OS, e.g. build the windows jar on linux):
//   ./gradlew :desktop:fatJar -Ptmpl.target=windows-x64
val fatJar by tasks.registering(Jar::class) {
    group = "distribution"
    archiveBaseName.set("tmpl-$tmplTarget")
    archiveVersion.set(appVersion)
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "dev.jyotiraditya.dmt.MainKt",
            "Implementation-Version" to appVersion,
            "Add-Opens" to "java.desktop/sun.awt.X11",
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "module-info.class")
    }
}

tasks.processResources {
    filesMatching("tmpl.properties") { expand("version" to appVersion) }
}


// .deb + .rpm + .tar.gz with distro-neutral dependencies (see packaging/linux/package.sh)
tasks.register<Exec>("linuxPackages") {
    group = "distribution"
    dependsOn("createReleaseDistributable")
    val appImage = layout.buildDirectory.dir("compose/binaries/main-release/app/tmpl")
    val out = layout.buildDirectory.dir("dist")
    val arch = if (tmplTarget.endsWith("arm64")) "aarch64" else "x86_64"
    commandLine(
        rootProject.file("packaging/linux/package.sh").absolutePath,
        appImage.get().asFile.absolutePath,
        appVersion,
        out.get().asFile.absolutePath,
        arch,
    )
}
