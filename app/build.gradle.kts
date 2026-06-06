import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties.local")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun secretProperty(name: String): String? {
    return keystoreProperties.getProperty(name)
        ?: providers.gradleProperty(name).orNull
        ?: System.getenv(name)
}

val appVersionCode = secretProperty("RACENAV_VERSION_CODE")?.toIntOrNull() ?: 399
val appVersionName = secretProperty("RACENAV_VERSION_NAME") ?: "2.9.90"

val releaseStoreFile = secretProperty("RACENAV_STORE_FILE")
    ?: secretProperty("KEYSTORE_PATH")
    ?: "${rootProject.projectDir}/racenav.keystore"
val releaseStorePassword = secretProperty("RACENAV_STORE_PASSWORD")
    ?: secretProperty("STORE_PASSWORD")
val releaseKeyAlias = secretProperty("RACENAV_KEY_ALIAS")
    ?: secretProperty("KEY_ALIAS")
    ?: "racenav"
val releaseKeyPassword = secretProperty("RACENAV_KEY_PASSWORD")
    ?: secretProperty("KEY_PASSWORD")

val hasGoogleServicesConfig = listOf(
    "google-services.json",
    "src/debug/google-services.json",
    "src/release/google-services.json"
).any { file(it).exists() }

if (hasGoogleServicesConfig) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
} else {
    logger.lifecycle("google-services.json not found; building without Google Services and Crashlytics Gradle plugins")
}

android {
    namespace = "com.andreykoff.racenav"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.andreykoff.racenav"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("org.maplibre.gl:android-sdk:10.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-location:21.1.0")
    // Firebase Crashlytics
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics")
}
