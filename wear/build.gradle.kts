plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.ultimate.wear"
    compileSdk = 35

    defaultConfig {
        // The same application ID and signing key as Ultimate+ on the phone: Wear OS only lets an app talk to its own
        // counterpart, and this is how the two recognise each other.
        applicationId = "app.ultimate.plus"
        minSdk = 30
        targetSdk = 35
        // The watch is its own install on its own device, so it keeps its own version.
        versionCode = 7
        versionName = "1.6.0"
    }

    signingConfigs {
        System.getenv("ULTIMATE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("ULTIMATE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ULTIMATE_KEY_ALIAS")
                keyPassword = System.getenv("ULTIMATE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    testImplementation("junit:junit:4.13.2")
}
