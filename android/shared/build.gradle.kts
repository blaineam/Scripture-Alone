plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Pure Kotlin on purpose: everything here is logic the phone and the watch must agree on (and agree
// with iOS on), so each piece is proven on the JVM, against the same files the iOS app ships.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // The JSON tree API only (no compiler plugin), as the phone's other readers use it.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // The sealed-package reader's Ed25519 and HKDF. Compile-only: the phone and the watch each supply
    // tink-android (the same classes), and the JVM tests the plain artifact.
    compileOnly("com.google.crypto.tink:tink:1.16.0")
    testImplementation("com.google.crypto.tink:tink:1.16.0")

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    systemProperty(
        "scripturealone.resources",
        rootProject.layout.projectDirectory.dir("../ScriptureAlone/Resources").asFile.absolutePath,
    )
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
