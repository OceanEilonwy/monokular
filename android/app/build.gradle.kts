plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.monokular"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "app.monokular"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // The project's Square application (docs/design.md §1). Public: the
        // till signs in with PKCE, so no secret is ever built in. Replace the
        // placeholder once the Square application exists.
        buildConfigField("String", "SQUARE_APPLICATION_ID", "\"${providers.gradleProperty("monokular.square.applicationId").getOrElse("sq0idp-REPLACE-ME")}\"")
        buildConfigField("boolean", "SQUARE_SANDBOX", providers.gradleProperty("monokular.square.sandbox").getOrElse("false"))
        // Where Square sends the sign-in back, and where it comes back into
        // the app: the same App Link in a release build.
        buildConfigField("String", "SQUARE_REDIRECT_URI", "\"https://monokular.monokulo.com/square\"")
        buildConfigField("String", "SQUARE_RETURN_URI", "\"https://monokular.monokulo.com/square\"")
        buildConfigField("String", "SQUARE_BASE_URL", "\"\"")
        // The project's Clover application and the region it is in (sandbox,
        // north_america, europe or latin_america). Public too: Clover's
        // low-trust sign-in is PKCE with no secret (decision 127). Replace the
        // placeholder once the Clover application exists.
        buildConfigField("String", "CLOVER_APPLICATION_ID", "\"${providers.gradleProperty("monokular.clover.applicationId").getOrElse("REPLACE-ME-CLOVER")}\"")
        buildConfigField("String", "CLOVER_ENVIRONMENT", "\"${providers.gradleProperty("monokular.clover.environment").getOrElse("north_america")}\"")
        // Clover sends the sign-in to a path under the application's Site URL, https only: the App Link.
        buildConfigField("String", "CLOVER_REDIRECT_URI", "\"https://monokular.monokulo.com/clover\"")
        buildConfigField("String", "CLOVER_RETURN_URI", "\"https://monokular.monokulo.com/clover\"")
        buildConfigField("String", "CLOVER_BASE_URL", "\"\"")
        // The project's Monokular Bell (docs/design.md §3), which Settings ›
        // Wake-ups offers by name. A placeholder until one is deployed.
        buildConfigField("String", "BELL_URL", "\"${providers.gradleProperty("monokular.bell.url").getOrElse("https://bell.monokulo.com")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // A debug build can talk to a Monokulo and a stand-in Square on
            // the developer's machine (docs/decisions/android.md).
            // -Pmonokular.square.baseUrl=http://10.0.2.2:8738 for tools/fake-square.
            val squareBase = providers.gradleProperty("monokular.square.baseUrl").getOrElse("")
            buildConfigField("String", "SQUARE_BASE_URL", "\"$squareBase\"")
            // The sign-in comes back on the debug scheme. Square only sends
            // it to https, so it goes through site/square/debug/, which opens
            // the scheme; the stand-in Square sends it to the scheme itself.
            // -Pmonokular.square.redirectUri=… names another such page.
            val squareRedirect = providers.gradleProperty("monokular.square.redirectUri")
                .getOrElse(if (squareBase.isEmpty()) "https://monokular.monokulo.com/square/debug/" else "monokular-debug://square")
            buildConfigField("String", "SQUARE_REDIRECT_URI", "\"$squareRedirect\"")
            buildConfigField("String", "SQUARE_RETURN_URI", "\"monokular-debug://square\"")
            // The same for Clover: -Pmonokular.clover.baseUrl=http://10.0.2.2:8740 for
            // tools/fake-clover, which plays both Clover's web and API hosts; else
            // site/clover/debug/ opens the scheme.
            val cloverBase = providers.gradleProperty("monokular.clover.baseUrl").getOrElse("")
            buildConfigField("String", "CLOVER_BASE_URL", "\"$cloverBase\"")
            val cloverRedirect = providers.gradleProperty("monokular.clover.redirectUri")
                .getOrElse(if (cloverBase.isEmpty()) "https://monokular.monokulo.com/clover/debug/" else "monokular-debug://clover")
            buildConfigField("String", "CLOVER_REDIRECT_URI", "\"$cloverRedirect\"")
            buildConfigField("String", "CLOVER_RETURN_URI", "\"monokular-debug://clover\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":sales"))
    implementation(project(":push"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)
    implementation(libs.okhttp)
    debugImplementation(libs.compose.ui.tooling)
    // Compose UI tests on the emulator (decision 152): the test activity comes with the manifest artifact.
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    // The runner and Espresso ui-test-junit4 brings reflect on InputManager.getInstance, gone in Android 16: these run there.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
