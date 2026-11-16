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
    }
}

rootProject.name = "CAM2"
include(":app")
include(":core:device-probe")
include(":core:camera-engine")
include(":core:computational-photo")
include(":core:raw-pipeline")
include(":core:video-engine")
include(":core:photo-editor")
include(":core:ai-masking")
