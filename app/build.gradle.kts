// AGP 9 compiles Kotlin itself (built-in Kotlin): do not apply org.jetbrains.kotlin.android.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "pt.lisbora"
    compileSdk = 37

    defaultConfig {
        applicationId = "pt.lisbora"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Where the app looks for data updates (the pipeline's `release`). Another address can be given
        // for testing, e.g. -Plisbora.dataManifestUrl=http://localhost:8000/manifest.json with `adb reverse tcp:8000 tcp:8000`
        // (debug builds allow plain HTTP to localhost only).
        val manifestUrl = providers.gradleProperty("lisbora.dataManifestUrl")
            .getOrElse("https://github.com/tomasmourato/LisBora/releases/download/datapack/manifest.json")
        buildConfigField("String", "DATA_MANIFEST_URL", "\"$manifestUrl\"")
    }

    // The Google Play upload key comes from ~/.gradle/gradle.properties, never from the repository:
    // lisbora.signing.storeFile, lisbora.signing.storePassword, lisbora.signing.keyAlias and
    // lisbora.signing.keyPassword. Without it, release builds are signed with the debug key: enough to
    // install and test them, not to publish (Google Play refuses debug-signed bundles).
    val upload = providers.gradleProperty("lisbora.signing.storeFile").orNull?.let { path ->
        signingConfigs.create("upload") {
            storeFile = file(path)
            storePassword = providers.gradleProperty("lisbora.signing.storePassword").get()
            keyAlias = providers.gradleProperty("lisbora.signing.keyAlias").get()
            keyPassword = providers.gradleProperty("lisbora.signing.keyPassword").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = upload ?: signingConfigs.getByName("debug")
        }
        // For :baselineprofile: like the release, but not shrunk, so the recorded startup profile names
        // the real classes (R8 maps them for the release), and debug-signed so it installs over it.
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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

    androidResources {
        // PMTiles tiles are already compressed; storing them as-is keeps install-time copying cheap.
        noCompress += "pmtiles"
    }
}

room {
    // Versioned schemas, so future migrations can be tested against them.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core:routing"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.maplibre.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    // Compiles the startup profile (src/main/baseline-prof.txt) on install, also outside Google Play.
    implementation(libs.androidx.profileinstaller)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Accessibility Test Framework checks (labels, touch targets, contrast) on every UI test action.
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Compose's ui-test pulls Espresso 3.5, which crashes on API 37 (InputManager.getInstance was removed).
    androidTestImplementation(libs.androidx.test.espresso.core)
}
