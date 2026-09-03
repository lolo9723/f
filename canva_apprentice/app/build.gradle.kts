plugins {
    id("com.android.application")
}

android {
    namespace = "com.emrah.canvaapprentice"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.emrah.canvaapprentice"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
