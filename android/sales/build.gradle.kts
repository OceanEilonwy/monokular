plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.monokular.sales"
    compileSdk = 37
    compileSdkMinor = 2
    defaultConfig {
        minSdk = 26
    }
    testOptions {
        // The receipt codec's library for this machine (see :receipt).
        unitTests.all { test ->
            test.systemProperty(
                "jna.library.path",
                File(rootDir.parentFile, "target/release").absolutePath,
            )
            test.dependsOn(":receipt:cargoHost")
        }
    }
}

dependencies {
    api(project(":core"))
    api(project(":receipt"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.jna)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
