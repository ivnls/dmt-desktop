plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.ksp) apply false
}

// metadata / lyrics live next to the desktop build: keep this build's outputs apart from it
listOf(":metadata", ":lyrics").forEach { path ->
    project(path) {
        layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("shared/${path.removePrefix(":")}"))
    }
}
