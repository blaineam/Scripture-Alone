// Riveduta 1927.
// The it reader's Bible (docs/localization.md), as iOS's on-demand `riv1927` pack. Fetched the
// first time it is chosen, or at first launch on a device set to its language — never waited on: the
// ASV opens at once and the reader switches when this arrives.
//
// A Play Asset Delivery pack. Its one file is copied in from the iOS app's resources at build time,
// as `syncBundledData` does for the app, so the repository holds exactly one copy of it; `src/` is
// generated and ignored. The pack's name, delivery and file are mirrored by `AssetPack` in the app
// (data/assets/AssetPack.kt), and `AssetPackDefinitionsTest` holds the two to each other.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("riv1927")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}

val syncPackContents by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.file("../ScriptureAlone/Resources/Bibles/RIV1927.sqlite"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.matching { it.name != syncPackContents.name && !it.name.startsWith("clean") }
    .configureEach { dependsOn(syncPackContents) }
