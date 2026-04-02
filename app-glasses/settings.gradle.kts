pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolution {
    repositories {
        google()
        mavenCentral()
        // No GitHub Packages needed — glasses app has no DAT SDK dependency
    }
}

rootProject.name = "duchess-glasses"
include(":app")
