import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("dagger.hilt.android.plugin")
}

// Native libraries (FFmpeg, ML Kit) are ~35 MB per CPU type, so only the ones that are
// needed get packaged:
//   - a plain build packages arm64-v8a, which is what phones use;
//   - Android Studio's Run button passes the CPU type of the device or emulator it targets;
//   - "-PallAbis" packages all four (about 110 MB more).
val packagedAbis: List<String>? = when {
    project.hasProperty("allAbis") -> null
    project.hasProperty("android.injected.build.abi") ->
        project.property("android.injected.build.abi").toString().split(",").map { it.trim() }
    else -> listOf("arm64-v8a")
}

android {
    namespace = "com.purrfectbytes.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.purrfectbytes.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"

        manifestPlaceholders["appAuthRedirectScheme"] = "com.googleusercontent.apps.667110250632-d9rq2oroo43aagg5g48f0mlga49rr0hv"

        // The API keys are compiled into the APK, where anyone holding the file can read
        // them. That is fine for an app that only lives on your own phone - don't share the APK.
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(FileInputStream(localPropertiesFile))
        }
        val geminiApiKey = localProperties.getProperty("GEMINI_API_KEY") ?: ""
        val anthropicApiKey = localProperties.getProperty("ANTHROPIC_API_KEY") ?: ""
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicApiKey\"")

        packagedAbis?.let { abis -> ndk { abiFilters += abis } }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }

    lint {
        // Only arm64 libraries are packaged on purpose, see packagedAbis above
        disable += "ChromeOsAbiSupport"
    }

    testOptions {
        unitTests {
            // Robolectric needs the app's resources (the video background, logo and QR code)
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Dependency Injection
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-compiler:2.48")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Media & Audio
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")

    // Image Loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // CameraX
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    // ML Kit Text Recognition
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Add support for Chinese, Japanese, Korean
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    // Add support for Devanagari script (Hindi, Sanskrit, etc.)
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    // Language identification
    implementation("com.google.mlkit:language-id:17.0.6")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // FFmpeg Kit
    implementation("io.github.jamaismagic.ffmpeg:ffmpeg-kit-lts-16kb:6.1.7")

    // Gemini AI
    implementation("com.google.ai.client.generativeai:generativeai:0.2.2")

    // YouTube Data API for native uploading, signed in through AppAuth
    implementation("com.google.api-client:google-api-client:1.33.0")
    implementation("com.google.apis:google-api-services-youtube:v3-rev222-1.25.0")
    implementation("com.google.http-client:google-http-client-gson:1.42.3")
    implementation("net.openid:appauth:0.11.1")
}
