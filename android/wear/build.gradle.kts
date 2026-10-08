import java.io.File
import java.security.MessageDigest
import java.nio.ByteBuffer
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val packagesDir: File = rootProject.layout.projectDirectory.dir("../ScriptureAlone/Resources/Packages").asFile

/**
 * A package's `wearables` term, from its plaintext header (docs/encrypted-translations.md) — no key needed.
 * Absent or "allowed" is allowed, anything else is not, exactly as the apps read it. A watch build never
 * carries a package its licence keeps off wearables; the watch would refuse to open it anyway.
 */
fun allowsWearables(file: File): Boolean = file.inputStream().use { input ->
    val preamble = input.readNBytes(14)
    val length = ByteBuffer.wrap(preamble, 10, 4).int
    val header = JsonSlurper().parseText(String(input.readNBytes(length), Charsets.UTF_8)) as Map<*, *>
    val term = (header["policy"] as? Map<*, *>)?.get("wearables")
    term == null || term == "allowed"
}

/**
 * Whether this build carries the sealed NASB 2020 — the watch's own Bible and its default, as on the
 * Apple Watch. The release workflow copies the package and key into the iOS resources from private
 * storage (docs/lockman/README.md); without them (a developer's build) the watch carries the ASV's
 * compact edition instead, so it still has something to read.
 */
val carriesNasb: Boolean = File(packagesDir, "NASB2020.sabible").exists() && File(packagesDir, "NASB2020-signing.pub").exists() &&
    allowsWearables(File(packagesDir, "NASB2020.sabible")).also { allowed ->
        if (!allowed) logger.warn("NASB2020.sabible is licensed off wearables: the watch carries the ASV's compact edition instead")
    }

/** The build's secret seed, masked exactly as the phone's (`contentKeySeedMasked` in app/build.gradle.kts). */
val contentKeySeedMasked: String = System.getenv("SA_CONTENT_KEY_SEED").orEmpty().let { raw ->
    if (raw.isEmpty()) return@let ""
    val seed = if (raw.all { it in "0123456789abcdefABCDEF" }) raw.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        else raw.toByteArray(Charsets.UTF_8)
    val pad = MessageDigest.getInstance("SHA-256").digest("scripture-alone-seed-pad-v1".toByteArray(Charsets.UTF_8))
    seed.indices.joinToString("") { i -> "%02x".format((seed[i].toInt() xor pad[i % pad.size].toInt()) and 0xff) }
}.also { masked ->
    check(!carriesNasb || masked.isNotEmpty()) {
        "NASB2020.sabible is present but SA_CONTENT_KEY_SEED is not set: the watch's default nobody could open"
    }
}

android {
    namespace = "com.blainemiller.scripturealone.wear"
    compileSdk = 36

    defaultConfig {
        // A Wear OS app shares its phone app's application ID: the Data Layer only connects apps with
        // the same ID and signing key.
        applicationId = "com.blainemiller.scripturealone"
        minSdk = 30
        targetSdk = 36
        // The phone and watch bundles share one package, so every uploaded bundle needs its own
        // versionCode: the watch numbers from 1,000,000 up, the phone below (scripts/play-publish.mjs).
        // The release workflow passes -PsaWearVersionCode and -PsaVersionName (the tag); local builds
        // use these.
        versionCode = providers.gradleProperty("saWearVersionCode").orNull?.toInt() ?: 1_000_005
        versionName = providers.gradleProperty("saVersionName").orNull ?: "1.0.0"
        check(versionCode!! >= 1_000_000) { "Wear OS versionCode $versionCode must be 1,000,000 or more" }
        buildConfigField("String", "CONTENT_KEY_SEED_MASKED", "\"$contentKeySeedMasked\"")
        buildConfigField("String", "WATCH_BUNDLED", if (carriesNasb) "\"NASB2020\"" else "\"ASV\"")
    }

    buildTypes.getByName("debug") {
        // JaCoCo line coverage of the JVM unit tests (android/build.gradle.kts › coverageReport).
        enableUnitTestCoverage = true
        // `-PappIdSuffix=…` suffixes the phone's debug build; the watch's follows it, so a debug pair
        // still shares one ID and can talk. Release builds never carry a suffix.
        providers.gradleProperty("appIdSuffix").orNull?.let { applicationIdSuffix = ".$it" }
    }

    // The same upload key as the phone app (SA_UPLOAD_* in ~/.gradle/gradle.properties, never the
    // repository): the Data Layer only pairs apps with the same ID *and* signing key.
    val uploadStore = providers.gradleProperty("SA_UPLOAD_STORE_FILE").orNull
    if (uploadStore != null) {
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
        buildConfig = true
    }

    // The watch editions and the Verse of the Day list, copied in at build time (`syncWatchData`).
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/watchData"))
    // The launcher icon, copied from the phone app's resources (`syncWatchIcon`) — one icon, one copy.
    sourceSets["main"].res.srcDir(layout.buildDirectory.dir("generated/watchIcon"))

    androidResources {
        // SQLite files and sealed packages must be stored uncompressed to be copied out cheaply, as in
        // the phone app.
        noCompress += listOf("sqlite", "sabible")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        // Robolectric (the Compose screens, activities, services and preferences on the JVM) reads the
        // merged resources and manifest.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // The edition reader is proven against the very `*-Watch.sqlite` files the watch ships.
            test.systemProperty(
                "scripturealone.watchResources",
                rootProject.layout.projectDirectory.dir("../ScriptureAloneWatch/Resources").asFile.absolutePath,
            )
            test.testLogging {
                events("failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

/**
 * The watch's own Bible — the sealed NASB 2020 with the signing keys of every sealed translation it may
 * hold, or, without it, the ASV's compact edition (`Tools/build_companion_data.py`) — and
 * `DailyVerses.json`, copied from the iOS sources so both watches read byte-identical data. Nothing is
 * committed twice.
 */
val syncWatchData by tasks.registering(Sync::class) {
    if (carriesNasb) {
        from(packagesDir) {
            include("NASB2020.sabible", "NASB2020-signing.pub", "NASB1995-signing.pub", "bundled-signing.pub")
        }
    } else {
        from(rootProject.layout.projectDirectory.dir("../ScriptureAloneWatch/Resources")) {
            include("ASV-Watch.sqlite")
        }
        from(packagesDir) {
            include("NASB1995-signing.pub", "bundled-signing.pub")
        }
    }
    from(rootProject.layout.projectDirectory.dir("../ScriptureAlone/Shared")) {
        include("DailyVerses.json")
    }
    into(layout.buildDirectory.dir("generated/watchData"))
}

val syncWatchIcon by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("app/src/main/res")) {
        include("mipmap-*/ic_launcher*", "drawable/ic_launcher_background.xml")
    }
    into(layout.buildDirectory.dir("generated/watchIcon"))
}
tasks.named("preBuild") { dependsOn(syncWatchData, syncWatchIcon) }

dependencies {
    implementation(project(":shared"))
    // The sealed-package reader's Ed25519 and HKDF (compile-only in :shared), as the phone has it.
    implementation("com.google.crypto.tink:tink-android:1.16.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Compose for Wear OS: the round-screen list, chips, time text and swipe-to-dismiss navigation.
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")

    // The Verse of the Day tile and complication.
    // Tiles 1.5+: 1.4.1 can throw a SecurityException on Wear OS 5 when targeting API 35+ (Play
    // flagged it on 1,000,002).
    implementation("androidx.wear.tiles:tiles:1.5.0")
    implementation("androidx.wear.protolayout:protolayout:1.3.0")
    implementation("androidx.wear.protolayout:protolayout-material:1.3.0")
    implementation("androidx.wear.protolayout:protolayout-expression:1.3.0")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    // The complications library pulls fragment 1.1.0, which Play reports as outdated.
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("com.google.guava:guava:33.3.1-android")

    // Following the phone's translation and library over the Data Layer.
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Screens, activities and services on the JVM (src/test/resources/robolectric.properties pins the SDK).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
}
