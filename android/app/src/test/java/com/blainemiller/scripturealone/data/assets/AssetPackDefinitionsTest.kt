package com.blainemiller.scripturealone.data.assets

import com.google.android.play.core.assetpacks.model.AssetPackErrorCode
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's pack table held to the Gradle modules that build the packs, to the build script's other
 * two lists (what stays in the base module, what a debug APK carries locally), and to iOS's packs —
 * so a pack renamed in one place and not the other fails here rather than on a reader's phone as a
 * download that never finds its file.
 */
class AssetPackDefinitionsTest {

    private val packsDir = File(System.getProperty("scripturealone.packs") ?: error("scripturealone.packs is not set — run through Gradle"))
    private val androidDir = packsDir.parentFile
    private val resources = File(System.getProperty("scripturealone.resources") ?: error("scripturealone.resources is not set"))

    private data class Module(val name: String, val delivery: String, val source: String)

    private fun module(pack: AssetPack): Module {
        val file = File(packsDir, "${pack.packName}/build.gradle.kts")
        assertTrue("no module for ${pack.packName}", file.isFile)
        val text = file.readText()
        fun find(pattern: String) = Regex(pattern).find(text)?.groupValues?.get(1) ?: error("$pattern not in ${file.path}")
        return Module(
            name = find("""packName\.set\("([^"]+)"\)"""),
            delivery = find("""deliveryType\.set\("([^"]+)"\)"""),
            source = find("""\.\./ScriptureAlone/Resources/([^"]+)"""),
        )
    }

    @Test
    fun everyPackHasAModuleWithTheSameNameDeliveryAndFile() {
        for (pack in AssetPack.entries) {
            val module = module(pack)
            assertEquals(pack.packName, module.name)
            assertEquals("${pack.packName} delivery", pack.delivery.gradleName, module.delivery)
            assertEquals("${pack.packName} file", pack.file, File(module.source).name)
            assertTrue("${module.source} is not in the iOS resources", File(resources, module.source).isFile)
        }
        // …and no module the table doesn't know.
        val modules = packsDir.listFiles { f -> File(f, "build.gradle.kts").isFile }.orEmpty().map { it.name }.toSet()
        assertEquals(AssetPack.entries.map { it.packName }.toSet(), modules)
    }

    @Test
    fun theAppAndSettingsListEveryPack() {
        val settings = File(androidDir, "settings.gradle.kts").readText()
        val app = File(androidDir, "app/build.gradle.kts").readText()
        for (pack in AssetPack.entries) {
            assertTrue("settings.gradle.kts doesn't include ${pack.packName}", settings.contains("\"${pack.packName}\""))
            assertTrue("the app doesn't list :${pack.packName}", app.contains("\":${pack.packName}\""))
        }
    }

    /** A debug APK carries every pack's file (so it runs with nothing to download); the base module none. */
    @Test
    fun aDebugApkCarriesEveryPackAndTheBaseModuleNone() {
        val app = File(androidDir, "app/build.gradle.kts").readText()
        val local = Regex("""syncLocalPackData[\s\S]*?include\(([^)]*)\)""").find(app)?.groupValues?.get(1) ?: error("no syncLocalPackData")
        val base = Regex("""syncBundledData[\s\S]*?include\(([^)]*)\)""").find(app)?.groupValues?.get(1) ?: error("no syncBundledData")
        for (pack in AssetPack.entries) {
            val source = module(pack).source
            assertTrue("debug APKs don't carry $source", local.contains("\"$source\""))
            assertFalse("the base module still carries $source", base.contains(pack.file))
        }
    }

    /** The same files as iOS's Background Assets packs, with iOS's one deliberate difference stated. */
    @Test
    fun thePacksAreIOSsPacks() {
        val manifests = File(resources, "../../Tools/asset-packs").canonicalFile
        for (pack in AssetPack.entries) {
            val manifest = File(manifests, "${pack.packName.replace('_', '-')}.json")
            assertTrue("no iOS manifest ${manifest.name}", manifest.isFile)
            val text = manifest.readText()
            assertTrue("${manifest.name} names another file", text.contains("\"fileDestination\": \"${pack.file}\""))
            val iosEssential = text.contains("\"essential\"")
            // The BSB arrives with the install on Android (Study's word data is keyed to it); on iOS
            // it is on demand. The ASV is in the iOS app bundle itself since App Review's endless
            // spinner (its pack, now on demand, only carries updates); on Android the install-time
            // pack is how it arrives with the install. Every other pack's timing matches.
            val expectedInstallTime = iosEssential || pack == AssetPack.BSB || pack == AssetPack.ASV
            assertEquals("${pack.packName} timing", expectedInstallTime, pack.delivery == AssetPack.Delivery.INSTALL_TIME)
        }
    }

    @Test
    fun lookups() {
        assertEquals(AssetPack.KJV, AssetPack.forTranslation("KJV"))
        assertNull(AssetPack.forTranslation("WEB"))
        assertNull(AssetPack.forTranslation("COMMENTARY"))
        assertEquals(AssetPack.COMMENTARY, AssetPack.forFile("Study.sqlite"))
        assertNull(AssetPack.forFile("CrossReferences.sqlite"))
        assertEquals("About 15 MB, downloaded once and kept for reading offline.", AssetPack.KJV.explanation)
    }

    // Play's statuses, in the reader's terms.

    @Test
    fun progressIsTheDownloadedFraction() {
        assertEquals(AssetState.Downloading(0.25f), AssetPackProgress.state(AssetPackStatus.DOWNLOADING, 25, 100))
        assertEquals(AssetState.Downloading(0f), AssetPackProgress.state(AssetPackStatus.PENDING, 0, 0))
        assertEquals(AssetState.Downloading(0f), AssetPackProgress.state(AssetPackStatus.DOWNLOADING, 5, 0))
        assertEquals(AssetState.Downloading(1f), AssetPackProgress.state(AssetPackStatus.TRANSFERRING, 100, 100))
        assertEquals(AssetState.NeedsConfirmation(0.5f), AssetPackProgress.state(AssetPackStatus.WAITING_FOR_WIFI, 50, 100))
        assertEquals(AssetState.NeedsConfirmation(0f), AssetPackProgress.state(AssetPackStatus.REQUIRES_USER_CONFIRMATION, 0, 100))
        assertNull(AssetPackProgress.state(AssetPackStatus.COMPLETED, 100, 100))
        assertTrue(AssetPackProgress.isTerminal(AssetPackStatus.COMPLETED))
        assertTrue(AssetPackProgress.isTerminal(AssetPackStatus.FAILED))
        assertTrue(AssetPackProgress.isTerminal(AssetPackStatus.CANCELED))
        assertFalse(AssetPackProgress.isTerminal(AssetPackStatus.WAITING_FOR_WIFI))
    }

    @Test
    fun failuresSayWhatTheReaderCanDo() {
        val kjv = AssetPack.KJV
        assertEquals("King James Version needs a connection to download.",
            AssetPackProgress.message(AssetPackErrorCode.NETWORK_ERROR, AssetPackStatus.FAILED, kjv))
        // Not installed from Play: no retry can succeed, and it says so.
        for (code in listOf(AssetPackErrorCode.API_NOT_AVAILABLE, AssetPackErrorCode.APP_UNAVAILABLE, AssetPackErrorCode.APP_NOT_OWNED)) {
            assertEquals(AssetPackProgress.notInThisCopy(kjv), AssetPackProgress.message(code, AssetPackStatus.FAILED, kjv))
        }
        assertTrue(AssetPackProgress.message(AssetPackErrorCode.INSUFFICIENT_STORAGE, AssetPackStatus.FAILED, kjv).contains("space"))
        assertTrue(AssetPackProgress.message(AssetPackErrorCode.NO_ERROR, AssetPackStatus.CANCELED, kjv).contains("cancelled"))
        assertTrue(AssetPackProgress.message(-100, AssetPackStatus.FAILED, kjv).contains("-100"))
    }
}
