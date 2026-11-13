import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The release key lives outside the repo (~/.config/wristtube). keystore.properties, which points
// at it, is git-ignored. Without it (a fresh clone) everything is signed with the debug key, which
// can't update an installed release.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")?.let { file(it).exists() } == true

android {
    namespace = "com.wristtube"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.wristtube"
        // Wear OS 4 and up. NewPipe Extractor needs desugaring below API 33, so 33 avoids it.
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Same key as releases, so a debug build installs over a release on the watch.
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
        getByName("release") {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            // A debuggable build is janky on watch hardware, and unminified it's several times
            // larger, which matters when the watch downloads its own updates over Wi-Fi.
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    // The watch's own voice / keyboard input, for search.
    implementation(libs.wear.input)
    // The chip on the watch face while something is playing.
    implementation(libs.wear.ongoing)

    // Playback: ExoPlayer, HLS for live streams, OkHttp underneath, a media session so audio keeps
    // playing with the screen off, and the Compose video surface.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.ui.compose)

    // Search and stream URLs, straight from YouTube, without an API key or a Google account.
    implementation(libs.newpipe.extractor)

    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
}
