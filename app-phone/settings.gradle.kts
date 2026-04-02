import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Load local.properties for GitHub Packages auth (Meta DAT SDK).
// Developers: copy local.properties.example → local.properties and fill in your GitHub PAT.
val localProps = Properties().also { props ->
    val localPropsFile = file("local.properties")
    if (localPropsFile.exists()) {
        props.load(localPropsFile.inputStream())
    }
}
val githubToken: String = localProps.getProperty("github_token") ?: System.getenv("GITHUB_TOKEN") ?: ""
val githubUser: String  = localProps.getProperty("github_user")  ?: System.getenv("GITHUB_USER")  ?: ""

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // Meta Wearables DAT SDK (mwdat-core, mwdat-camera, mwdat-mockdevice).
        // Requires a GitHub PAT with read:packages scope.
        // See local.properties.example for setup instructions.
        // Falls through gracefully with empty credentials — Gradle will warn on sync but
        // MockDeviceKit in test sources lets unit tests run without hardware or credentials.
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = githubUser
                password = githubToken
            }
        }
    }
}

rootProject.name = "duchess-companion"
include(":app")
