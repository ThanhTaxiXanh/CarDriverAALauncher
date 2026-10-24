import java.io.File

plugins { id("com.android.application") }

val signingPath = System.getenv("RELEASE_STORE_FILE")
val signingStorePassword = System.getenv("KEYSTORE_PASSWORD")
val signingAlias = System.getenv("KEY_ALIAS")
val signingKeyPassword = System.getenv("KEY_PASSWORD")
val releaseSigningConfigured = listOf(signingPath, signingStorePassword, signingAlias, signingKeyPassword)
    .all { !it.isNullOrBlank() }
val mapboxToken = System.getenv("MAPBOX_PUBLIC_TOKEN") ?: ""

android {
    namespace = "com.thanhtaxixanh.cardriverlauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.thanhtaxixanh.cardriverlauncher"
        minSdk = 26
        targetSdk = 37
        versionCode = (System.getenv("BUILD_VERSION_CODE")?.toIntOrNull() ?: 1)
        versionName = "1.0.$versionCode"
        // Public Mapbox token (pk.*) is an identifier, not a private download token.
        buildConfigField("String", "MAPBOX_PUBLIC_TOKEN", "\"${mapboxToken.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    signingConfigs {
        create("releaseKey") {
            if (releaseSigningConfigured) {
                storeFile = File(signingPath!!)
                storePassword = signingStorePassword
                keyAlias = signingAlias
                keyPassword = signingKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("releaseKey")
        }
        getByName("debug") { isDebuggable = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}

// Fail early for CI release builds rather than silently uploading an unsigned APK.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    doFirst {
        check(releaseSigningConfigured) { "Release signing secrets missing" }
        check(File(signingPath!!).isFile) { "Keystore file missing: $signingPath" }
    }
}

dependencies {
    implementation("androidx.activity:activity:1.11.0")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    // Official Mapbox MapSurface renderer for Android Auto (no VirtualDisplay required in stable mode).
    implementation("com.mapbox.extension:maps-androidauto:11.33.0")
    testImplementation("junit:junit:4.13.2")
}
