// The Hebrew and Greek behind every word, with a lexicon — downloaded when the reader asks for it in
// Study, as iOS's on-demand `study-interlinear` pack. (Play pack names can't contain a dash.)
//
// A Play Asset Delivery pack. Its one file is copied in from the iOS app's resources at build time,
// as `syncBundledData` does for the app, so the repository holds exactly one copy of it; `src/` is
// generated and ignored. The pack's name, delivery and file are mirrored by `AssetPack` in the app
// (data/assets/AssetPack.kt), and `AssetPackDefinitionsTest` holds the two to each other.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("study_interlinear")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}

val syncPackContents by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.file("../ScriptureAlone/Resources/Study/Interlinear.sqlite"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.matching { it.name != syncPackContents.name && !it.name.startsWith("clean") }
    .configureEach { dependsOn(syncPackContents) }
