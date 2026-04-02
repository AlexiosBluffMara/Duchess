pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // No GitHub Packages needed — glasses app has no Meta DAT SDK dependency.
        // All dependencies (TFLite, LiteRT, AndroidX) are on Maven Central / Google Maven.
    }
}

rootProject.name = "duchess-glasses"
include(":app")
