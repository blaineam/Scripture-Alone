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
        include("Bibles/*.sqlite", "Study/*.sqlite", "Packages/*.sabible")
        // The ASV ships sealed as ASV.sabible; the plaintext store is only the packaging tool's input.
        exclude("Bibles/ASV.sqlite")
        eachFile { path = name }          // flatten, as the iOS bundle does
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/bundledData"))
}
tasks.named("preBuild") { dependsOn(syncBundledData) }

dependencies {
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
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
