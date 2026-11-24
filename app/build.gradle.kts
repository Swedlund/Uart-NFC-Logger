plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.nfclogger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nfclogger"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }


}

dependencies {
    implementation("com.gitee.lochy:dkcloudid-uart-android-sdk:v2.2.4")
    implementation("androidx.core:core:1.12.0")
}