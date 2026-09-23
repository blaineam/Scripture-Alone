plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
        // versionCode: one sequential counter, the phone taking the next code and the watch the one
        // after (scripts/play-publish.mjs). 1,000,001–1,000,004 are a retired Wear range. The release
        // workflow passes -PsaWearVersionCode and -PsaVersionName (the tag); local builds use these.
        versionCode = providers.gradleProperty("saWearVersionCode").orNull?.toInt() ?: 4
        versionName = providers.gradleProperty("saVersionName").orNull ?: "1.0.0"
        check(versionCode!! < 1_000_000) { "Wear OS versionCode $versionCode is in the retired 1,000,000+ range" }
    }

    buildTypes.getByName("debug") {
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
    }

    // The watch editions and the Verse of the Day list, copied in at build time (`syncWatchData`).
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/watchData"))
    // The launcher icon, copied from the phone app's resources (`syncWatchIcon`) — one icon, one copy.
    sourceSets["main"].res.srcDir(layout.buildDirectory.dir("generated/watchIcon"))

    androidResources {
        // SQLite files must be stored uncompressed to be copied out cheaply, as in the phone app.
        noCompress += listOf("sqlite")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
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
 * The Apple Watch's compact editions — `*-Watch.sqlite`, verse text and red letters without the
 * phone's layout or search index (`Tools/build_companion_data.py`) — and `DailyVerses.json`, copied
 * from the iOS sources so both watches read byte-identical data. Nothing is committed twice.
 */
val syncWatchData by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("../ScriptureAloneWatch/Resources")) {
        include("*-Watch.sqlite")
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

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
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
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
}
