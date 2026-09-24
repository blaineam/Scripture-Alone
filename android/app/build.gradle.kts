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
        // Local builds use these; the release workflow (.github/workflows/android.yml) passes
        // -PsaVersionCode (Play's highest phone code + 1) and -PsaVersionName (the tag).
        // Below 1,000,000; the Wear OS bundle numbers above it — see android/wear/build.gradle.kts.
        versionCode = providers.gradleProperty("saVersionCode").orNull?.toInt() ?: 3
        versionName = providers.gradleProperty("saVersionName").orNull ?: "1.0.0"
        check(versionCode!! < 1_000_000) { "phone versionCode $versionCode is in the Wear OS 1,000,000+ range" }
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

    // The upload key for Play. It lives outside the repository — the four SA_UPLOAD_* properties
    // go in ~/.gradle/gradle.properties (or ORG_GRADLE_PROJECT_* environment variables) — and signs
    // only the bundle uploaded to Play, which re-signs it with the app-signing key it holds.
    val uploadStore = providers.gradleProperty("SA_UPLOAD_STORE_FILE").orNull
    if (uploadStore != null && !providers.gradleProperty("debugSignedRelease").isPresent) {
        val upload = signingConfigs.create("upload") {
            storeFile = file(uploadStore)
            storePassword = providers.gradleProperty("SA_UPLOAD_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("SA_UPLOAD_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("SA_UPLOAD_KEY_PASSWORD").get()
        }
        buildTypes.getByName("release").signingConfig = upload
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
    assetPacks += listOf(
        ":asv", ":bsb", ":kjv", ":study_commentary", ":study_interlinear",
        ":cuvs", ":bungo", ":lut1912", ":lsg", ":rvr1909", ":krv", ":blivre", ":riv1927",
    )

    // `-PsideloadApk` with `assembleRelease`: a release APK for installing outside Google Play, with
    // every pack's file in its own assets (about 225 MB with the big-8 Bibles), since only Play can
    // deliver an on-demand pack. Signed with the upload key, not Play's app-signing key, so a Play install can't update it
    // in place. Never with a bundle, where the packs themselves carry these files.
    if (providers.gradleProperty("sideloadApk").isPresent) {
        check(gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }) {
            "-PsideloadApk is for assembleRelease; a bundle's packs already carry these files"
        }
        sourceSets["release"].assets.srcDir(layout.buildDirectory.dir("generated/localPackData"))
    }

    androidResources {
        // SQLite files must be stored uncompressed: Android cannot open a compressed asset as a
        // database, and would otherwise have to inflate 100 MB into memory on every launch.
        noCompress += listOf("sqlite", "sabible")
        // The reader can pick the app's language apart from the phone's (Settings › Apps › Scripture
        // Alone › Language), as iOS offers per-app language: the list is generated from the values-*
        // folders Levi fills, with src/main/res/resources.properties naming English as the default.
        generateLocaleConfig = true
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
 * res/xml/shortcuts.xml (app shortcuts and App Actions), from src/main/shortcuts/shortcuts.xml with
 * `${applicationId}` filled in. A shortcut's intent must name its package literally — a resource
 * reference is not resolved there — and a debug build with -PappIdSuffix must open itself.
 */
abstract class GenerateShortcuts : DefaultTask() {
    @get:InputFile abstract val template: RegularFileProperty
    @get:Input abstract val applicationId: Property<String>
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction fun generate() {
        val file = output.file("xml/shortcuts.xml").get().asFile
        file.parentFile.mkdirs()
        file.writeText(template.get().asFile.readText().replace("\${applicationId}", applicationId.get()))
    }
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<GenerateShortcuts>("generate${variant.name.replaceFirstChar { it.uppercase() }}Shortcuts") {
            template.set(layout.projectDirectory.file("src/main/shortcuts/shortcuts.xml"))
            applicationId.set(variant.applicationId)
        }
        variant.sources.res?.addGeneratedSourceDirectory(task, GenerateShortcuts::output)
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
        // Nave's Topical Bible (Study/Topics.sqlite, 1.2 MB) for the Topics directory, as on iOS.
        include(
            "Study/CrossReferences.sqlite", "Study/Context.sqlite", "Study/Basemap.bin", "Study/Topics.sqlite",
            "Packages/bundled-signing.pub",
        )
        eachFile { path = name }          // flatten, as the iOS bundle does
        includeEmptyDirs = false
    }
    // Verse of the Day: the same curated list, and so the same passage on the same day, as iOS.
    from(rootProject.layout.projectDirectory.dir("../ScriptureAlone/Shared")) {
        include("DailyVerses.json")
    }
    // The curated life themes of the Topics directory: the same passages as iOS (Tools/build_topics.py).
    from(rootProject.layout.projectDirectory.dir("../ScriptureAloneCore/Sources/ScriptureAloneCore/Resources")) {
        include("LifeThemes.json")
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
        include(
            "Packages/ASV.sabible", "Bibles/BSB.sqlite", "Bibles/KJV.sqlite", "Study/Study.sqlite", "Study/Interlinear.sqlite",
            // The big-8 locales' Bibles — docs/localization.md.
            "Bibles/CUVS.sqlite", "Bibles/BUNGO.sqlite", "Bibles/LUT1912.sqlite", "Bibles/LSG.sqlite",
            "Bibles/RVR1909.sqlite", "Bibles/KRV.sqlite", "Bibles/BLIVRE.sqlite", "Bibles/RIV1927.sqlite",
        )
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
    // Notes and favorites in the device's search (data/appsearch/) — Spotlight on iOS. The platform's own
    // AppSearch store (Android 12+), so nothing extra ships in the APK; results carry deep links.
    // 1.1.0-beta01, not 1.1.0: the stable release requires AGP 8.9.1 and this build is on 8.7.3.
    implementation("androidx.appsearch:appsearch:1.1.0-beta01")
    implementation("androidx.appsearch:appsearch-platform-storage:1.1.0-beta01")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.1.0")
    // Camera notes (ui/camera/, data/camera/): CameraX for the live scanner, and ML Kit's text recognizer
    // with the Latin model bundled in the APK, so a slide is read on the device, offline, with no Play
    // services download — VisionKit and Vision on iOS. camera-mlkit-vision maps its boxes onto the preview.
    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.camera:camera-mlkit-vision:$cameraX")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // PDF import (data/importer/PDFBibleReader.kt): the text layer of a Bible PDF — each glyph with its
    // font, size and position — which Android has no API for. Apache PDFBox's Android port, Apache-2.0,
    // pure Java; only text extraction is used. The JVM tests read the same glyphs through PDFBox itself.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Reads ASV.sqlite — the plaintext the sealed ASV was built from — as the tests' ground truth.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    // The PDF reader's glyphs on the JVM: the PDFBox release pdfbox-android is ported from.
    testImplementation("org.apache.pdfbox:pdfbox:2.0.27")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // The host activity createComposeRule needs (VerseNodesTest); debug builds only.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
