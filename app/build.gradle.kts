plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.ultimate"
    compileSdk = 35

    // Screens are drawn in a simulated Android in unit tests, which needs the merged manifest and resources.
    testOptions { unitTests { isIncludeAndroidResources = true } }

    defaultConfig {
        applicationId = "app.ultimate"
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // CI companion builds supply their own increasing code; local builds stay above every companion default.
        versionCode = System.getenv("COMPANION_VERSION_CODE")?.toIntOrNull() ?: 35
        versionName = "1.31.0"
    }

    // One codebase, several apps. Each edition switches tools on with HAS_* flags; constants let R8 drop the others entirely.
    // A single-tool edition makes that tool its own home screen.
    // Build one with e.g. `gradle :app:assembleSqueezeRelease`. Add an edition by copying a block and its src/<edition>/ folder.
    flavorDimensions += "edition"
    productFlavors {
        create("ultimate") {
            dimension = "edition"
            applicationId = "app.ultimate"
            resValue("string", "app_name", "Ultimate")
            buildConfigField("boolean", "HAS_SQUEEZE", "true")
            buildConfigField("boolean", "HAS_AUDIO", "true")
            buildConfigField("boolean", "HAS_PLUCK", "true")
            buildConfigField("boolean", "HAS_SOUND", "true")
            buildConfigField("boolean", "HAS_TRAFFIC", "false")
            buildConfigField("boolean", "HAS_STUDIO", "true")
            buildConfigField("boolean", "HAS_CHECKUP", "true")
            buildConfigField("boolean", "HAS_AI", "false")
            buildConfigField("boolean", "HAS_NETWORK", "false")
            buildConfigField("boolean", "HAS_SENSORS", "true")
            buildConfigField("boolean", "HAS_RECORDER", "true")
            buildConfigField("boolean", "HAS_PHOTOS", "true")
            buildConfigField("boolean", "HAS_PROMPTER", "true")
            buildConfigField("boolean", "HAS_SEND", "false")
            buildConfigField("boolean", "HAS_SCREENTIME", "true")
            buildConfigField("boolean", "HAS_FILES", "true")
            buildConfigField("boolean", "HAS_INBOX", "true")
            buildConfigField("boolean", "HAS_CLIPS", "true")
            buildConfigField("boolean", "HAS_TERMINAL", "true")
            buildConfigField("boolean", "HAS_ORB", "true")
            buildConfigField("boolean", "HAS_RULES", "true")
        }
        // The full toolbox plus the traffic inspector. Needs the internet permission, so it is a separate app from the offline Ultimate.
        create("ultimateplus") {
            proguardFile("src/ultimateplus/java/app/ultimate/ssh/mina-rules.pro")
            dimension = "edition"
            applicationId = if (providers.gradleProperty("companionBuild").orNull == "true") "app.ultimate.companion" else "app.ultimate.plus"
            resValue("string", "app_name", if (providers.gradleProperty("companionBuild").orNull == "true") "Ultimate Companion" else "Ultimate+")
            buildConfigField("boolean", "HAS_SQUEEZE", "true")
            buildConfigField("boolean", "HAS_AUDIO", "true")
            buildConfigField("boolean", "HAS_PLUCK", "true")
            buildConfigField("boolean", "HAS_SOUND", "true")
            buildConfigField("boolean", "HAS_TRAFFIC", "true")
            buildConfigField("boolean", "HAS_STUDIO", "true")
            buildConfigField("boolean", "HAS_CHECKUP", "true")
            buildConfigField("boolean", "HAS_AI", "true")
            buildConfigField("boolean", "HAS_NETWORK", "true")
            buildConfigField("boolean", "HAS_SENSORS", "true")
            buildConfigField("boolean", "HAS_RECORDER", "true")
            buildConfigField("boolean", "HAS_PHOTOS", "true")
            buildConfigField("boolean", "HAS_PROMPTER", "true")
            buildConfigField("boolean", "HAS_SEND", "true")
            buildConfigField("boolean", "HAS_SCREENTIME", "true")
            buildConfigField("boolean", "HAS_FILES", "true")
            buildConfigField("boolean", "HAS_INBOX", "true")
            buildConfigField("boolean", "HAS_CLIPS", "true")
            buildConfigField("boolean", "HAS_TERMINAL", "true")
            buildConfigField("boolean", "HAS_ORB", "true")
            buildConfigField("boolean", "HAS_RULES", "true")
        }
        create("studio") {
            dimension = "edition"
            applicationId = "app.ultimate.studio"
            resValue("string", "app_name", "Studio")
            buildConfigField("boolean", "HAS_SQUEEZE", "false")
            buildConfigField("boolean", "HAS_AUDIO", "false")
            buildConfigField("boolean", "HAS_PLUCK", "false")
            buildConfigField("boolean", "HAS_SOUND", "false")
            buildConfigField("boolean", "HAS_TRAFFIC", "false")
            buildConfigField("boolean", "HAS_STUDIO", "true")
            buildConfigField("boolean", "HAS_CHECKUP", "false")
            buildConfigField("boolean", "HAS_AI", "false")
            buildConfigField("boolean", "HAS_NETWORK", "false")
            buildConfigField("boolean", "HAS_SENSORS", "false")
            buildConfigField("boolean", "HAS_RECORDER", "false")
            buildConfigField("boolean", "HAS_PHOTOS", "false")
            buildConfigField("boolean", "HAS_PROMPTER", "false")
            buildConfigField("boolean", "HAS_SEND", "false")
            buildConfigField("boolean", "HAS_SCREENTIME", "false")
            buildConfigField("boolean", "HAS_FILES", "false")
            buildConfigField("boolean", "HAS_INBOX", "false")
            buildConfigField("boolean", "HAS_CLIPS", "false")
            buildConfigField("boolean", "HAS_TERMINAL", "false")
            buildConfigField("boolean", "HAS_ORB", "false")
            buildConfigField("boolean", "HAS_RULES", "false")
        }
        create("traffic") {
            dimension = "edition"
            applicationId = "app.ultimate.traffic"
            resValue("string", "app_name", "Traffic Inspector")
            buildConfigField("boolean", "HAS_SQUEEZE", "false")
            buildConfigField("boolean", "HAS_AUDIO", "false")
            buildConfigField("boolean", "HAS_PLUCK", "false")
            buildConfigField("boolean", "HAS_SOUND", "false")
            buildConfigField("boolean", "HAS_TRAFFIC", "true")
            buildConfigField("boolean", "HAS_STUDIO", "false")
            buildConfigField("boolean", "HAS_CHECKUP", "false")
            buildConfigField("boolean", "HAS_AI", "false")
            buildConfigField("boolean", "HAS_NETWORK", "false")
            buildConfigField("boolean", "HAS_SENSORS", "false")
            buildConfigField("boolean", "HAS_RECORDER", "false")
            buildConfigField("boolean", "HAS_PHOTOS", "false")
            buildConfigField("boolean", "HAS_PROMPTER", "false")
            buildConfigField("boolean", "HAS_SEND", "false")
            buildConfigField("boolean", "HAS_SCREENTIME", "false")
            buildConfigField("boolean", "HAS_FILES", "false")
            buildConfigField("boolean", "HAS_INBOX", "false")
            buildConfigField("boolean", "HAS_CLIPS", "false")
            buildConfigField("boolean", "HAS_TERMINAL", "false")
            buildConfigField("boolean", "HAS_ORB", "false")
            buildConfigField("boolean", "HAS_RULES", "false")
        }
        create("squeeze") {
            dimension = "edition"
            applicationId = "app.squeeze"
            resValue("string", "app_name", "Squeeze")
            buildConfigField("boolean", "HAS_SQUEEZE", "true")
            buildConfigField("boolean", "HAS_AUDIO", "false")
            buildConfigField("boolean", "HAS_PLUCK", "false")
            buildConfigField("boolean", "HAS_SOUND", "false")
            buildConfigField("boolean", "HAS_TRAFFIC", "false")
            buildConfigField("boolean", "HAS_STUDIO", "false")
            buildConfigField("boolean", "HAS_CHECKUP", "false")
            buildConfigField("boolean", "HAS_AI", "false")
            buildConfigField("boolean", "HAS_NETWORK", "false")
            buildConfigField("boolean", "HAS_SENSORS", "false")
            buildConfigField("boolean", "HAS_RECORDER", "false")
            buildConfigField("boolean", "HAS_PHOTOS", "false")
            buildConfigField("boolean", "HAS_PROMPTER", "false")
            buildConfigField("boolean", "HAS_SEND", "false")
            buildConfigField("boolean", "HAS_SCREENTIME", "false")
            buildConfigField("boolean", "HAS_FILES", "false")
            buildConfigField("boolean", "HAS_INBOX", "false")
            buildConfigField("boolean", "HAS_CLIPS", "false")
            buildConfigField("boolean", "HAS_TERMINAL", "false")
            buildConfigField("boolean", "HAS_ORB", "false")
            buildConfigField("boolean", "HAS_RULES", "false")
        }
        create("audio") {
            dimension = "edition"
            applicationId = "app.ultimate.audio"
            resValue("string", "app_name", "Video to Audio")
            buildConfigField("boolean", "HAS_SQUEEZE", "false")
            buildConfigField("boolean", "HAS_AUDIO", "true")
            buildConfigField("boolean", "HAS_PLUCK", "false")
            buildConfigField("boolean", "HAS_SOUND", "false")
            buildConfigField("boolean", "HAS_TRAFFIC", "false")
            buildConfigField("boolean", "HAS_STUDIO", "false")
            buildConfigField("boolean", "HAS_CHECKUP", "false")
            buildConfigField("boolean", "HAS_AI", "false")
            buildConfigField("boolean", "HAS_NETWORK", "false")
            buildConfigField("boolean", "HAS_SENSORS", "false")
            buildConfigField("boolean", "HAS_RECORDER", "false")
            buildConfigField("boolean", "HAS_PHOTOS", "false")
            buildConfigField("boolean", "HAS_PROMPTER", "false")
            buildConfigField("boolean", "HAS_SEND", "false")
            buildConfigField("boolean", "HAS_SCREENTIME", "false")
            buildConfigField("boolean", "HAS_FILES", "false")
            buildConfigField("boolean", "HAS_INBOX", "false")
            buildConfigField("boolean", "HAS_CLIPS", "false")
            buildConfigField("boolean", "HAS_TERMINAL", "false")
            buildConfigField("boolean", "HAS_ORB", "false")
            buildConfigField("boolean", "HAS_RULES", "false")
        }
        create("pluck") {
            dimension = "edition"
            applicationId = "app.pluck"
            resValue("string", "app_name", "Pluck")
            buildConfigField("boolean", "HAS_SQUEEZE", "false")
            buildConfigField("boolean", "HAS_AUDIO", "false")
            buildConfigField("boolean", "HAS_PLUCK", "true")
            buildConfigField("boolean", "HAS_SOUND", "false")
            buildConfigField("boolean", "HAS_TRAFFIC", "false")
            buildConfigField("boolean", "HAS_STUDIO", "false")
            buildConfigField("boolean", "HAS_CHECKUP", "false")
            buildConfigField("boolean", "HAS_AI", "false")
            buildConfigField("boolean", "HAS_NETWORK", "false")
            buildConfigField("boolean", "HAS_SENSORS", "false")
            buildConfigField("boolean", "HAS_RECORDER", "false")
            buildConfigField("boolean", "HAS_PHOTOS", "false")
            buildConfigField("boolean", "HAS_PROMPTER", "false")
            buildConfigField("boolean", "HAS_SEND", "false")
            buildConfigField("boolean", "HAS_SCREENTIME", "false")
            buildConfigField("boolean", "HAS_FILES", "false")
            buildConfigField("boolean", "HAS_INBOX", "false")
            buildConfigField("boolean", "HAS_CLIPS", "false")
            buildConfigField("boolean", "HAS_TERMINAL", "false")
            buildConfigField("boolean", "HAS_ORB", "false")
            buildConfigField("boolean", "HAS_RULES", "false")
        }
    }

    // Releases are signed with the project's permanent key when CI provides it (GitHub secrets), so every published
    // APK can update the last one. Local builds fall back to the debug key and cannot update a published install.
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
            // Shrinking stays on for every build: without it the APK carries every icon and library class (~53 MB instead of ~7).
            // Companion previews keep readable crash stacks by skipping only the renaming step.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (providers.gradleProperty("companionBuild").orNull == "true") proguardFiles("proguard-companion.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Keep MINA's license notices and combine its filesystem-provider registrations.
        resources.merges += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE", "META-INF/services/**")
    }
}

// Editions without the AI assistant get a stub, so the code that calls Anthropic exists only in Ultimate+.
android.sourceSets {
    // SSH tests live in the requested common test directory but compile only for Ultimate+.
    (getByName("test").java as org.gradle.api.tasks.util.PatternFilterable).exclude("app/ultimate/ssh/**")
    getByName("testUltimateplus").java.srcDir("src/test/java/app/ultimate/ssh")
    listOf("ultimate", "studio", "traffic", "squeeze", "audio", "pluck").forEach { getByName(it).java.srcDir("src/noai/java") }
}

dependencies {
    "ultimateplusImplementation"("org.apache.sshd:sshd-core:2.20.1")
    "ultimateplusImplementation"("org.apache.sshd:sshd-sftp:2.20.1")
    // Ed25519 support on Android releases without Java's native EdEC implementation.
    "ultimateplusImplementation"("net.i2p.crypto:eddsa:0.3.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // QR code for opening Send to computer on another phone or tablet.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Teleprompter: camera preview and recording.
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Pure-Java port of the LAME MP3 encoder (LGPL 2.1+). See app/src/main/java/javax/sound for the Android shim it needs.
    implementation("de.sciss:jump3r:1.0.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Robolectric runs Android framework code on the JVM, for tests that start real services and windows (the floating orb).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.work:work-testing:2.10.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Robolectric's Android framework jar comes through Gradle (and its proxy settings) instead of Robolectric's own downloader.
// Two versions of one artifact would collapse into the newer, so each framework jar gets its own configuration.
val robolectricAndroid14: Configuration by configurations.creating
val robolectricAndroid15: Configuration by configurations.creating
dependencies {
    robolectricAndroid14("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
    robolectricAndroid15("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
}
val robolectricJars = tasks.register<Copy>("robolectricJars") {
    from(robolectricAndroid14, robolectricAndroid15)
    into(layout.buildDirectory.dir("robolectric"))
}
tasks.withType<Test>().configureEach {
    dependsOn(robolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric").get().asFile.path)
}
