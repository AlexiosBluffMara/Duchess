import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val localProps = Properties().apply {
    val f = file("local.properties")
    if (f.exists()) load(f.inputStream())
}

dependencyResolution {
    repositories {
        google()
        mavenCentral()

        // Meta DAT SDK — requires GitHub PAT with read:packages scope
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = localProps.getProperty("github_user", "")
                password = localProps.getProperty("github_token", "")
            }
        }
    }
}

rootProject.name = "duchess-companion"
include(":app")
