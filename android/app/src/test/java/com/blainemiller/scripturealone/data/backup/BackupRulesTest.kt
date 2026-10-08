package com.blainemiller.scripturealone.data.backup

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.blainemiller.scripturealone.data.listen.ListenKeys
import com.blainemiller.scripturealone.data.prefs.ReaderKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The backup rules (`res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`) against
 * [SettingsBackup]: the reader's choices go, and nothing device-local, cached or credential-bearing
 * does. Reads the files from the source tree — unit tests run in the module directory.
 */
class BackupRulesTest {

    private data class Rule(val domain: String, val path: String)

    private fun document(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$name")).documentElement

    private fun includes(section: Element): Set<Rule> {
        val nodes = section.getElementsByTagName("include")
        return (0 until nodes.length).map { i ->
            val e = nodes.item(i) as Element
            Rule(e.getAttribute("domain"), e.getAttribute("path"))
        }.toSet()
    }

    private fun section(root: Element, tag: String): Element {
        val nodes = root.getElementsByTagName(tag)
        assertEquals("one <$tag>", 1, nodes.length)
        return nodes.item(0) as Element
    }

    private val cloud by lazy { section(document("data_extraction_rules.xml"), "cloud-backup") }
    private val transfer by lazy { section(document("data_extraction_rules.xml"), "device-transfer") }
    private val legacy by lazy { document("backup_rules.xml").also { assertEquals("full-backup-content", it.tagName) } }

    private val expected: Set<Rule> =
        SettingsBackup.SHARED_PREFS.map { Rule("sharedpref", "$it.xml") }.toSet() +
            Rule("file", SettingsBackup.READER_SETTINGS) +
            SettingsBackup.USER_DATA_FILES.map { Rule("file", it) } +
            Rule("file", "${SettingsBackup.KEEPSAKES}/") +
            Rule("file", "${SettingsBackup.IMPORTS}/")

    @Test
    fun allThreeListsAreSettingsBackupsList() {
        assertEquals(expected, includes(cloud))
        assertEquals(expected, includes(transfer))
        assertEquals(expected, includes(legacy))
    }

    @Test
    fun theReadersChoicesAreIncluded() {
        for (rules in listOf(includes(cloud), includes(transfer), includes(legacy))) {
            // Appearance (accent, theme, type…), translation, position, Listen, search toggles.
            assertTrue(Rule("file", "datastore/reader.preferences_pb") in rules)
            // The API.Bible translations picked, the keepsake owner and dedication, study, compare.
            for (name in listOf("translations", "legacy", "study", "compare")) assertTrue(name, Rule("sharedpref", "$name.xml") in rules)
            assertTrue(Rule("file", "userdata.sqlite") in rules)
        }
    }

    @Test
    fun nothingDeviceLocalOrSecretIsIncluded() {
        val forbidden = listOf(
            "no_backup", "cache", "OnlineTranslations", "packs", "vault", "bundled",
            "online-keys", "widgets", "systemSearch", "rating", "appWidget", "-shm", "-journal",
        )
        for (rules in listOf(includes(cloud), includes(transfer), includes(legacy))) {
            for (rule in rules) {
                // Whole domains, the data root and external storage are never taken in bulk.
                assertTrue(rule.toString(), rule.domain in setOf("file", "sharedpref"))
                assertTrue(rule.toString(), rule.path.isNotBlank() && !rule.path.contains(".."))
                for (word in forbidden) {
                    val first = rule.path.substringBefore('/')
                    assertFalse("$rule matches $word", first == word || first.removeSuffix(".xml") == word || rule.path.endsWith(word))
                }
            }
            for (name in SettingsBackup.DEVICE_SHARED_PREFS) assertFalse(name, Rule("sharedpref", "$name.xml") in rules)
        }
        // Excludes would mean the list isn't include-only; there are none.
        for (root in listOf(cloud, transfer, legacy)) assertEquals(0, root.getElementsByTagName("exclude").length)
    }

    @Test
    fun theManifestUsesTheRulesAndTheAgent() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (attribute in listOf(
            """android:allowBackup="true"""",
            """android:backupAgent=".data.backup.SettingsBackupAgent"""",
            """android:fullBackupOnly="true"""",
            """android:fullBackupContent="@xml/backup_rules"""",
            """android:dataExtractionRules="@xml/data_extraction_rules"""",
        )) assertTrue(attribute, attribute in manifest)
    }

    /** Every SharedPreferences file the app opens is classified, so a new one can't be forgotten. */
    @Test
    fun everySharedPreferencesFileIsClassified() {
        val names = mutableSetOf<String>()
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            if ("getSharedPreferences(" !in text) return@forEach
            Regex("""getSharedPreferences\("([^"]+)"""").findAll(text).forEach { names += it.groupValues[1] }
            Regex("""getSharedPreferences\((PREFS|FILE),""").findAll(text).forEach { use ->
                val const = use.groupValues[1]
                Regex("""const val $const = "([^"]+)"""").find(text)?.let { names += it.groupValues[1] }
            }
        }
        assertEquals(setOf("study", "compare", "legacy", "translations", "systemSearch", "widgets", "rating"), names)
        assertEquals(names, (SettingsBackup.SHARED_PREFS + SettingsBackup.DEVICE_SHARED_PREFS).toSet())
    }

    @Test
    fun theReaderStoreIsTheFileTheRulesName() {
        val source = File("src/main/java/com/blainemiller/scripturealone/data/prefs/ReaderPrefs.kt").readText()
        assertTrue("preferencesDataStore(name = \"reader\")" in source)
        assertEquals("datastore/reader.preferences_pb", SettingsBackup.READER_SETTINGS)
    }

    @Test
    fun aRestoreForgetsThatListenAskedForNotifications() {
        val prefs = mutablePreferencesOf(
            ListenKeys.ASKED_NOTIFICATIONS to true,
            ListenKeys.VOICE to "en-us-x-iol-local",
            ReaderKeys.ACCENT to "sunrise",
            ReaderKeys.TRANSLATION to "BSB",
        )
        SettingsBackup.forgetDeviceLocal(prefs)
        assertNull(prefs[ListenKeys.ASKED_NOTIFICATIONS])
        assertEquals("sunrise", prefs[ReaderKeys.ACCENT])
        assertEquals("BSB", prefs[ReaderKeys.TRANSLATION])
        assertEquals("en-us-x-iol-local", prefs[ListenKeys.VOICE])
    }

    @Test
    fun theSettingsAlwaysGoAndTheLibraryOnlyWhileItFits() {
        val mb = 1L shl 20
        val sizes = mapOf("reader" to 2_000L, "prefs" to 1_000L, "db" to 10 * mb, "wal" to 2 * mb, "keep1" to 8 * mb, "keep2" to 5 * mb)
        val settings = listOf("reader", "prefs")
        val library = listOf(listOf("db", "wal"), listOf("keep1"), listOf("keep2"))
        val quota = 25 * mb
        // 12 MB + 8 MB fit under 24 MB; another 5 MB doesn't.
        assertEquals(listOf("reader", "prefs", "db", "wal", "keep1"), SettingsBackup.plan(settings, library, sizes::getValue, quota))
        // A library too big for the quota is left out whole — never the database without its log.
        val huge = sizes + ("db" to 30 * mb)
        assertEquals(listOf("reader", "prefs", "keep1", "keep2"), SettingsBackup.plan(settings, library, huge::getValue, quota))
        // Device-to-device transfer reports a far larger quota: everything goes.
        assertEquals(settings + library.flatten(), SettingsBackup.plan(settings, library, huge::getValue, Long.MAX_VALUE / 2))
        // A transport that reports no quota is held to the documented 25 MB.
        assertEquals(listOf("reader", "prefs", "keep1", "keep2"), SettingsBackup.plan(settings, library, huge::getValue, 0))
    }

    @Test
    fun smallImportsGoAfterTheLibrarySmallestFirst() {
        val mb = 1L shl 20
        val sizes = mapOf(
            "reader" to 2_000L, "db" to 3 * mb, "wal" to 0L, "keep" to 2 * mb,
            "NT" to 5 * mb, "Bible" to 15 * mb, "Study" to 60 * mb, "Portion" to mb,
        )
        val imports = listOf("Bible", "NT", "Study", "Portion")
        val quota = 25 * mb
        val groups = SettingsBackup.importGroups(imports, sizes::getValue, quota)
        // Over the cap is never offered to the cloud; the rest smallest first.
        assertEquals(listOf(listOf("Portion"), listOf("NT"), listOf("Bible")), groups)
        val library = listOf(listOf("db", "wal"), listOf("keep")) + groups
        // 5 MB of library, then 1 + 5 MB of imports; the 15 MB Bible no longer fits under 24 MB.
        assertEquals(listOf("reader", "db", "wal", "keep", "Portion", "NT"), SettingsBackup.plan(listOf("reader"), library, sizes::getValue, quota))
        // With a small library, one whole Bible fits.
        val small = sizes + ("db" to mb / 2) + ("keep" to 0L) + ("NT" to 7 * mb)
        assertEquals(
            listOf("reader", "db", "wal", "keep", "Portion", "Bible"),
            SettingsBackup.plan(listOf("reader"), listOf(listOf("db", "wal"), listOf("keep")) + listOf(listOf("Portion"), listOf("Bible")), small::getValue, quota),
        )
        // A device transfer carries every import, even the study Bible.
        assertEquals(4, SettingsBackup.importGroups(imports, sizes::getValue, Long.MAX_VALUE / 2).size)
        assertTrue(SettingsBackup.importBacksUp(16 * mb))
        assertFalse(SettingsBackup.importBacksUp(16 * mb + 1))
    }

    @Test
    fun theAgentPreparesTheImportsDirectoryForARestore() {
        val agent = File("src/main/java/com/blainemiller/scripturealone/data/backup/SettingsBackupAgent.kt").readText()
        assertTrue("File(filesDir, SettingsBackup.IMPORTS).mkdirs()" in agent)
        val library = File("src/main/java/com/blainemiller/scripturealone/data/translations/TranslationLibrary.kt").readText()
        assertTrue("File(context.applicationContext.filesDir, \"${SettingsBackup.IMPORTS}\")" in library)
    }
}
