// The licensed NASB 1995, sealed — fetched the first time it is chosen, as on iOS (`nasb1995`).
//
// Its package is never in this repository: the release workflow copies it from private storage into
// the iOS resources (docs/lockman/README.md), and this module syncs it from there. Without it the app
// leaves this pack out (`assetPacks` in app/build.gradle.kts) and doesn't offer the NASB 1995.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("nasb1995")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}

val syncPackContents by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.file("../ScriptureAlone/Resources/Packages/NASB1995.sabible"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.matching { it.name != syncPackContents.name && !it.name.startsWith("clean") }
    .configureEach { dependsOn(syncPackContents) }
