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
        // The project's Monokular Bell (docs/design.md §3), which Settings ›
        // Wake-ups offers by name. A placeholder until one is deployed.
        buildConfigField("String", "BELL_URL", "\"${providers.gradleProperty("monokular.bell.url").getOrElse("https://bell.monokulo.com")}\"")
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
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)
    implementation(libs.okhttp)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
