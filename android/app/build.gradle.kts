plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.blainemiller.scripturealone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.blainemiller.scripturealone"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes.getByName("debug") {
        // `-PappIdSuffix=study` installs a debug build beside the others (…scripturealone.study), so
        // parallel work on one emulator doesn't overwrite each other's app or its saved state.
        providers.gradleProperty("appIdSuffix").orNull?.let { applicationIdSuffix = ".$it" }
    }

    // Release has no signing key in the repository — Play App Signing holds the real one. For local
    // bundletool testing only, `-PdebugSignedRelease` signs a release build with the debug key, so a
    // release bundle can be installed on an emulator. Never used for an upload.
    if (providers.gradleProperty("debugSignedRelease").isPresent) {
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("debug")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true
    }
    buildFeatures {
        compose = true
    }

    // The bundled databases are copied in from the iOS app's resources at build time — see
    // `syncBundledData` below — so there is exactly one copy of each in the repository.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/bundledData"))

    // The Bibles and study databases are Play Asset Delivery packs (`packs/`), which reach a device
    // only through Google Play or bundletool. A debug APK — `installDebug`, Android Studio's Run —
    // carries them in its own assets instead, so it runs with everything local and nothing to
    // download, as iOS's Debug build copies its pack sources into the bundle. Never in release, so a
    // broken pack can't hide behind a bundled copy; and not in a debug *bundle*, where the packs
    // themselves carry the files (bundletool refuses the same asset in two modules) and the
    // download path is what's being tested. `-PlocalPacks=true|false` overrides.
    val localPacks = providers.gradleProperty("localPacks").orNull?.toBooleanStrictOrNull()
        ?: gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }
    if (localPacks) sourceSets["debug"].assets.srcDir(layout.buildDirectory.dir("generated/localPackData"))
    assetPacks += listOf(":asv", ":bsb", ":kjv", ":study_commentary", ":study_interlinear")

    androidResources {
        // SQLite files must be stored uncompressed: Android cannot open a compressed asset as a
        // database, and would otherwise have to inflate 100 MB into memory on every launch.
        noCompress += listOf("sqlite", "sabible")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.all { test ->
            // The .sabible tests read the real package and its plaintext source straight from the
            // iOS resources, so the proof runs against the bytes that ship, not a copy.
            test.systemProperty(
                "scripturealone.resources",
                rootProject.layout.projectDirectory.dir("../ScriptureAlone/Resources").asFile.absolutePath,
            )
            // The asset-pack modules, so a test can hold the app's pack table to their build files.
            test.systemProperty("scripturealone.packs", rootProject.layout.projectDirectory.dir("packs").asFile.absolutePath)
            test.testLogging {
                events("passed", "failed", "skipped")
                showStandardStreams = true
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

/**
 * Copies the bundled Bible, study and context databases from the iOS app's resources.
 *
 * The iOS app is the source of truth for these files. Copying them in at build time rather than
 * committing a second copy keeps the two apps reading byte-identical data, which is the only way
 * "the same verse says the same thing on both platforms" stays true without anyone checking.
 */
val syncBundledData by tasks.registering(Sync::class) {
    val iosResources = rootProject.layout.projectDirectory.dir("../ScriptureAlone/Resources")
    from(iosResources) {
        // What stays in the base module, as on iOS (`Tools/asset-packs/README.md`): cross references
        // (so they never wait on the commentary download), the context database and map, and the
        // publisher key the ASV's signature is checked against — a trust anchor that arrived by the
        // same channel as the package it vouches for would vouch for nothing. The Bibles and the
        // commentary and interlinear databases are asset packs (`packs/`).
        include("Study/CrossReferences.sqlite", "Study/Context.sqlite", "Study/Basemap.bin", "Packages/bundled-signing.pub")
        eachFile { path = name }          // flatten, as the iOS bundle does
        includeEmptyDirs = false
    }
    // Verse of the Day: the same curated list, and so the same passage on the same day, as iOS.
    from(rootProject.layout.projectDirectory.dir("../ScriptureAlone/Shared")) {
        include("DailyVerses.json")
    }
    into(layout.buildDirectory.dir("generated/bundledData"))
}
tasks.named("preBuild") { dependsOn(syncBundledData) }

/**
 * The asset packs' files, for a debug APK's own assets — see `localPacks` above. The same list as the
 * pack modules; `AssetPackDefinitionsTest` checks the app's table against those.
 */
val syncLocalPackData by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("../ScriptureAlone/Resources")) {
        include("Packages/ASV.sabible", "Bibles/BSB.sqlite", "Bibles/KJV.sqlite", "Study/Study.sqlite", "Study/Interlinear.sqlite")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/localPackData"))
}
tasks.named("preBuild") { dependsOn(syncLocalPackData) }

dependencies {
    // The canon, verse keys, Verse of the Day and the widget snapshot — shared with the Wear OS app.
    implementation(project(":shared"))
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Android's own SQLite has no FTS5 ("no such module: fts5", verified on API 35 / SQLite 3.44.3),
    // and every bundled Bible database searches with it. This ships a SQLite build that does.
    implementation("androidx.sqlite:sqlite-bundled:2.5.0")
    // Play Asset Delivery: fetching the on-demand packs (KJV, commentary, original languages) from
    // Google Play's own hosting — free, no server — with progress for the reader's banner.
    implementation("com.google.android.play:asset-delivery:2.3.0")
    // The .sabible reader: Tink for Ed25519 (no platform Ed25519 before API 33) and HKDF; the JSON
    // tree API of kotlinx.serialization for the header, because org.json is a stub on the JVM.
    implementation("com.google.crypto.tink:tink-android:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Reading position and appearance — the iOS app's UserDefaults `reader.*` keys.
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Home-screen widgets (ui/widget/), their start-up sync, and telling the Wear OS app the reader's
    // translation and library over the Data Layer.
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    // The reader's ESV and API.Bible keys, carried to their other devices end-to-end encrypted — iCloud
    // Keychain's part on iOS (data/online/OnlineKeySync.kt). Free, no server.
    implementation("com.google.android.gms:play-services-auth-blockstore:16.4.0")
    // Play In-App Review — StoreKit's requestReview on iOS (ui/appearance/RatingPrompt.kt). A no-op until
    // the app is installed from a Play listing.
    implementation("com.google.android.play:review:2.0.2")
    // Listen: a media session over the TextToSpeech reader — lock screen, notification and headset
    // controls, and the mediaPlayback foreground service that keeps reading with the screen off.
    implementation("androidx.media3:media3-session:1.5.1")
    // Camera notes (ui/camera/, data/camera/): CameraX for the live scanner, and ML Kit's text recognizer
    // with the Latin model bundled in the APK, so a slide is read on the device, offline, with no Play
    // services download — VisionKit and Vision on iOS. camera-mlkit-vision maps its boxes onto the preview.
    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.camera:camera-mlkit-vision:$cameraX")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Reads ASV.sqlite — the plaintext the sealed ASV was built from — as the tests' ground truth.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // The host activity createComposeRule needs (VerseNodesTest); debug builds only.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
