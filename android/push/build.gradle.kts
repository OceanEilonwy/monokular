plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.monokular.push"
    compileSdk = 37
    compileSdkMinor = 2
    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.unifiedpush.connector)
    implementation(libs.kotlinx.coroutines.core)
}
