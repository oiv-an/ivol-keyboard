plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.android.inputmethod.latin"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "pro.ivol.keyboard"
        minSdk = 24
        targetSdk = 30
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    androidResources {
        noCompress += "dict"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { buildConfig = true }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.viewpager:viewpager:1.1.0")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    // IVOL: sherpa-onnx (Apache-2.0) for on-device GigaAM speech recognition.
    // The AAR is not committed (52 MB). Run ./tools/fetch-sherpa-onnx.sh once.
    implementation(files("libs/sherpa-onnx-2.0.3.aar"))
    // IVOL: Google ML Kit on-device translation RU<->EN
    implementation("com.google.mlkit:translate:17.0.3")
}
