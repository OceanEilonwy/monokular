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
        buildConfigField("String", "SQUARE_REDIRECT_URI", "\"https://monokular.app/square\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // A debug build can talk to a Monokulo and a stand-in Square on
            // the developer's machine (docs/decisions/android.md).
            buildConfigField("String", "SQUARE_REDIRECT_URI", "\"monokular-debug://square\"")
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
}
