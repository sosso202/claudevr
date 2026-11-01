plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lyes.vrclaude"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lyes.vrclaude"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    // One fixed key, so each new build installs over the last one.
    signingConfigs {
        create("fixed") {
            storeFile = file("vrclaude.keystore")
            storePassword = "android"
            keyAlias = "vrclaude"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        noCompress += "task"
    }
}

dependencies {
    implementation("com.google.ar:core:1.44.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
