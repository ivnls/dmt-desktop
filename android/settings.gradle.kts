pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // jsyn (midi playback in media3) is only published there
        maven { url = uri("https://jitpack.io") }
    }
}

// The android app of tmpl. It shares the tag + lyrics modules with the desktop build in ../
rootProject.name = "tmpl-android"
include(":app")
include(":metadata")
include(":lyrics")
project(":metadata").projectDir = file("../metadata")
project(":lyrics").projectDir = file("../lyrics")
