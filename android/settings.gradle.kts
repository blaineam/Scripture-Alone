pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "ScriptureAlone"
include(":app")
// Plain Kotlin shared by the phone and the watch: the canon, verse keys, the Verse of the Day pick,
// the widget snapshot and the phone ↔ watch vocabulary. No Android types, so it tests on the JVM.
include(":shared")
// The Wear OS app — the Apple Watch app's counterpart.
include(":wear")
