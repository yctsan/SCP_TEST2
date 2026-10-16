plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.clipmerger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.clipmerger"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
