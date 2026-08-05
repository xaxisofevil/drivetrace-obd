import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Ingest server URL/token live in local.properties (gitignored, machine-specific),
// never committed, matching sdk.dir's existing pattern in this file.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.ericbarone.drivetrace"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ericbarone.drivetrace"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "INGEST_BASE_URL", "\"${localProperties.getProperty("ingest.baseUrl", "")}\"")
        buildConfigField("String", "INGEST_TOKEN", "\"${localProperties.getProperty("ingest.token", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Room (local persistence)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Location
    implementation("com.google.android.gms:play-services-location:21.4.0")

    // OBD command parsing (Apache-2.0). Pinned to a commit past v1.4.1 (latest tag) via JitPack:
    // this commit fixes RPMCommand's unbounded-byte-fold bug (confirmed: RPM read back as 3.8
    // trillion on a real drive before this pin). Several other commands have the identical bug
    // still unfixed even here; those are worked around locally in obd/SafeCommands.kt instead.
    implementation("com.github.eltonvs:kotlin-obd-api:30014eb6e8cd35334ba8f7ea627500f6b1942ff5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Best-effort live streaming to the home ingest server (see server/)
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
