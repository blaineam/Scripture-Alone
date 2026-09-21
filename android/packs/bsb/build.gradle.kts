// The BSB — installed with the app. On iOS it is an on-demand pack; here it arrives with the install
// because Study's original-language words are keyed to its text, and it is the plain, searchable
// companion a fresh install reads beside the ASV.
//
// A Play Asset Delivery pack. Its one file is copied in from the iOS app's resources at build time,
// as `syncBundledData` does for the app, so the repository holds exactly one copy of it; `src/` is
// generated and ignored. The pack's name, delivery and file are mirrored by `AssetPack` in the app
// (data/assets/AssetPack.kt), and `AssetPackDefinitionsTest` holds the two to each other.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("bsb")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}

val syncPackContents by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.file("../ScriptureAlone/Resources/Bibles/BSB.sqlite"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.matching { it.name != syncPackContents.name && !it.name.startsWith("clean") }
    .configureEach { dependsOn(syncPackContents) }
