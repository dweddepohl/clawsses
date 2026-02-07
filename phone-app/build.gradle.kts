import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Load Rokid credentials from local.properties (needed for SN verification)
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

android {
    namespace = "com.claudeglasses.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.claudeglasses.phone"
        minSdk = 28  // Required by CXR-M SDK
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Rokid credentials for SN verification during Bluetooth connection
        // clientSecret = AES key used to decrypt snEncryptContent (from .lc file)
        // accessKey = rokidAccount identifier
        buildConfigField("String", "ROKID_CLIENT_SECRET", "\"${localProperties.getProperty("rokid.clientSecret", "")}\"")
        buildConfigField("String", "ROKID_ACCESS_KEY", "\"${localProperties.getProperty("rokid.accessKey", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md"
            )
        }
    }
}

// Bundle glasses-app APK into phone-app assets so it can be installed via SDK/ADB
// Builds glasses-app debug APK and copies it as "glasses-app-release.apk" in assets
val bundleGlassesApk by tasks.registering(Copy::class) {
    dependsOn(":glasses-app:assembleDebug")
    from("${project(":glasses-app").buildDir}/outputs/apk/debug/glasses-app-debug.apk")
    into("src/main/assets")
    rename { "glasses-app-release.apk" }
}

tasks.named("preBuild") {
    dependsOn(bundleGlassesApk)
}

dependencies {
    implementation(project(":shared"))

    // Rokid CXR-M SDK (Phone side)
    implementation("com.rokid.cxr:client-m:1.0.8")

    // Android Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Networking for WebSocket/SSH
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // Speech Recognition
    implementation("androidx.core:core-ktx:1.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ADB over WiFi for APK installation on glasses
    implementation("dev.mobile:dadb:1.2.10")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
