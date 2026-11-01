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
        versionCode = 2
        versionName = "0.2"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
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

    // Builds libcardboard_sdk_jni.so: the JNI glue for the Cardboard SDK's Java API.
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
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
    // Cardboard SDK, built from googlevr/cardboard by the CI workflow.
    implementation(files("libs/cardboard-sdk.aar"))
    // Cardboard SDK's own dependencies (a file AAR doesn't bring them along).
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.gms:play-services-vision:20.1.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.protobuf:protobuf-javalite:3.19.4")
    compileOnly("org.jspecify:jspecify:1.0.0")

    implementation("com.google.ar:core:1.44.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
