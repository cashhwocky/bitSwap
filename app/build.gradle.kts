plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ksPath: String? = System.getenv("BITSWAP_KEYSTORE_PATH")
val hasKeystore = ksPath != null && file(ksPath).exists()

android {
    namespace = "com.bitdroid.swap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bitdroid.swap"
        minSdk = 31
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        create("release") {
            if (hasKeystore) {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("BITSWAP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BITSWAP_KEY_ALIAS")
                keyPassword = System.getenv("BITSWAP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasKeystore) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // zstd-jni ships a native lib
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // unpacking ROM payloads (XZ, BZ2, ZSTD)
    implementation("org.tukaani:xz:1.10")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.luben:zstd-jni:1.5.6-8@aar")
}
