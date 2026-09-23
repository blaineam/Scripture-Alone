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
// Play Asset Delivery packs: the Bibles and study databases, delivered beside the app rather than
// inside its base module, whose download Google Play caps. iOS ships the same files as Background
// Assets packs — see `Tools/asset-packs/` and data/assets/AssetPack.kt.
// The big-8 locales' Bibles are on-demand packs too (docs/localization.md).
for (pack in listOf(
    "asv", "bsb", "kjv", "study_commentary", "study_interlinear",
    "cuvs", "bungo", "lut1912", "lsg", "rvr1909", "krv", "blivre", "riv1927",
)) {
    include(":$pack")
    project(":$pack").projectDir = file("packs/$pack")
}
