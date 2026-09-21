// Calvin, Gill and Jamieson-Fausset-Brown — downloaded when the reader asks for them in Study, as
// iOS's on-demand `study-commentary` pack. (Play pack names can't contain a dash, so an underscore.)
//
// A Play Asset Delivery pack. Its one file is copied in from the iOS app's resources at build time,
// as `syncBundledData` does for the app, so the repository holds exactly one copy of it; `src/` is
// generated and ignored. The pack's name, delivery and file are mirrored by `AssetPack` in the app
// (data/assets/AssetPack.kt), and `AssetPackDefinitionsTest` holds the two to each other.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("study_commentary")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}

val syncPackContents by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.file("../ScriptureAlone/Resources/Study/Study.sqlite"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.matching { it.name != syncPackContents.name && !it.name.startsWith("clean") }
    .configureEach { dependsOn(syncPackContents) }
