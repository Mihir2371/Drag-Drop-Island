plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}
android {
    namespace = "com.example.draggo"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.draggo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-prototype"
    }
}
dependencies {
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
}
