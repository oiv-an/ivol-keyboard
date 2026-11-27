import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// IVOL: defaults for the AI (magic wand) button, taken from a git-ignored local file.
// The repository is public, so no key is ever committed: without the file the app simply
// starts with empty AI settings and the user fills them in on the settings screen.
val aiDev = Properties().apply {
    val f = rootProject.file("ai-dev.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun aiDefault(key: String): String = (aiDev.getProperty(key) ?: "").trim()

// IVOL: release signing. Values live in a git-ignored key.properties in the project root
// (storeFile, storePassword, keyAlias, keyPassword). Without it a release build is unsigned.
val keyProps = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.android.inputmethod.latin"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "pro.ivol.keyboard"
        minSdk = 24
        targetSdk = 30
        versionCode = 12
        versionName = "0.5.5"
        ndk { abiFilters += listOf("arm64-v8a") }
        // Public builds ship with empty AI settings; the user fills them in the app.
        buildConfigField("String", "IVOL_AI_BASE_URL", "\"\"")
        buildConfigField("String", "IVOL_AI_API_KEY", "\"\"")
        buildConfigField("String", "IVOL_AI_MODEL", "\"\"")
        buildConfigField("String", "IVOL_AI_REASONING", "\"\"")
    }

    signingConfigs {
        if (keyProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Local developer defaults for the magic wand, debug only: a release APK is
            // published to everyone and must never carry anybody's API key.
            buildConfigField("String", "IVOL_AI_BASE_URL", "\"${aiDefault("ivol.ai.baseUrl")}\"")
            buildConfigField("String", "IVOL_AI_API_KEY", "\"${aiDefault("ivol.ai.apiKey")}\"")
            buildConfigField("String", "IVOL_AI_MODEL", "\"${aiDefault("ivol.ai.model")}\"")
            buildConfigField("String", "IVOL_AI_REASONING", "\"${aiDefault("ivol.ai.reasoning")}\"")
        }
        release {
            // R8 is off: LatinIME, sherpa-onnx and ML Kit reach Java code from JNI and
            // reflection, so a shrunk build is a silent-crash risk for no real gain here.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
    // IVOL: on-device language detection for translate (model bundled, ~1 MB)
    implementation("com.google.mlkit:language-id:17.0.6")
}
