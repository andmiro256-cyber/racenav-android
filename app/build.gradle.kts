plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.andreykoff.racenav"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.andreykoff.racenav"
        minSdk = 26
        targetSdk = 35
        versionCode = 52
        versionName = "2.0.5"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_PATH")
            val keystoreAlias = System.getenv("KEY_ALIAS") ?: "racenav"
            val keystorePass = System.getenv("STORE_PASSWORD") ?: ""
            val keyPass = System.getenv("KEY_PASSWORD") ?: ""
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = keystorePass
                keyAlias = keystoreAlias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val ks = signingConfigs.getByName("release")
            if (ks.storeFile != null) signingConfig = ks
        }
        debug {
            val ks = signingConfigs.getByName("release")
            if (ks.storeFile != null) signingConfig = ks
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
}
