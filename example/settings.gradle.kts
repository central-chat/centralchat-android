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
        // The published library, resolved the way a customer resolves it. This
        // example is the thing people copy, so it must not take a shortcut none
        // of them can take.
        maven("https://static.central.chat/maven")
    }
}

rootProject.name = "centralchat-example"

// To develop against the library source beside this example instead of the
// published version, uncomment the line below — Gradle substitutes the local
// project for chat.central:centralchat because that build declares the same
// group and name.
// includeBuild("../../lib/android")

include(":app")
