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
        // The publisher key is what the ASV's signature is checked against — the key the iOS build pins.
        include("Bibles/*.sqlite", "Study/*.sqlite", "Study/Basemap.bin", "Packages/*.sabible", "Packages/bundled-signing.pub")
        // The ASV ships sealed as ASV.sabible; the plaintext store is only the packaging tool's input.
        exclude("Bibles/ASV.sqlite")
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
    // Listen: a media session over the TextToSpeech reader — lock screen, notification and headset
    // controls, and the mediaPlayback foreground service that keeps reading with the screen off.
    implementation("androidx.media3:media3-session:1.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Reads ASV.sqlite — the plaintext the sealed ASV was built from — as the tests' ground truth.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
