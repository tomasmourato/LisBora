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

rootProject.name = "LisBora"

// The data workflow (.github/workflows/data.yml) passes -Plisbora.semApp=true: it only runs the
// pipeline, so it needs neither the Android SDK nor the app's configuration.
if (providers.gradleProperty("lisbora.semApp").orNull != "true") {
    include(":app")
    include(":baselineprofile")
}
include(":core:routing")
include(":pipeline")
