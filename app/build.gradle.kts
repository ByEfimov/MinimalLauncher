plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ravium.teyeslauncher"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ravium.teyeslauncher"
        minSdk = 29
        // TEYES CC3 = Android 10. targetSdk 29 keeps package visibility / background rules simple.
        targetSdk = 29
        // Version comes from gradle.properties → appVersion (CI adds the build number: 1.1 → 1.1.<run>)
        val appVersion = (project.findProperty("appVersion") as String?) ?: "1.0.0"
        val parts = appVersion.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 } + listOf(0, 0, 0)
        versionName = appVersion
        versionCode = parts[0] * 1_000_000 + parts[1] * 10_000 + parts[2]   // 1.1.37 → 1010037
        // OTA updates: GitHub "owner/repo" whose Releases contain the APK (see gradle.properties).
        // Яндекс Карты: free MapKit key from developer.tech.yandex.ru (empty → OpenStreetMap)
        buildConfigField("String", "YANDEX_MAPKIT_KEY", "\"${(project.findProperty("yandexMapKitKey") as String?) ?: ""}\"")
        // TEYES CC3 is ARM; skip x86 MapKit libraries to keep the APK small
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("String", "UPDATE_REPO", "\"${(project.findProperty("updateRepo") as String?) ?: ""}\"")
        buildConfigField("String", "ACTIVATION_URL", "\"${(project.findProperty("activationUrl") as String?) ?: ""}\"")
    }
    signingConfigs {
        // One shared key for all builds (your Mac and mine) so updates install over each other.
        create("shared") {
            storeFile = rootProject.file("keystore/minimal-drive.jks")
            storePassword = "minimaldrive"
            keyAlias = "minimaldrive"
            keyPassword = "minimaldrive"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            // Small APK for the head unit.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    // store MapKit .so files compressed — halves the APK size
    packaging { jniLibs { useLegacyPackaging = true } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Map in the main card (OpenStreetMap / CARTO tiles, no API key needed)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Яндекс Карты: map, traffic, routes, search (used only when yandexMapKitKey is set)
    implementation("com.yandex.android:maps.mobile:4.45.0-full")
}
