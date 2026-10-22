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
    }
}

rootProject.name = "monokular"

// core: pure Kotlin (JVM), no Android: model, connect links, protocol.
// push: UnifiedPush registration and messages.
// app: the Compose app.
include(":core", ":push", ":app")
