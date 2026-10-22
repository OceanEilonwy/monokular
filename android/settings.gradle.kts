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
// sales: finding Monero sales in the POS (Square), and the till's sales.
// push: UnifiedPush registration and messages.
// receipt: ORF receipts in the payment code (orf-codec, from Rust).
// app: the Compose app.
include(":core", ":receipt", ":sales", ":push", ":app")
