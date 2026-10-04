package com.blainemiller.scripturealone.data.guide

import com.blainemiller.scripturealone.data.guide.UserGuide.Block
import com.blainemiller.scripturealone.text.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The User Guide's package format — `UserGuideTests.swift`, case for case, plus the store. */
class UserGuideTest {
    companion object {
        /** Every block kind the converter writes, in the converter's own shape (the Swift tests' sample). */
        val SAMPLE = """
        {"schema":1,"language":"en","title":"Scripture Alone — User Guide","contents":"Contents",
         "cover":{"eyebrow":"User Guide","title":"Scripture Alone Bible","subtitle":"Everything.","edition":"v1","images":["01-reader.png"]},
         "chapters":[{"title":"Welcome","summary":"What it is","lede":[{"text":"Hello "},{"text":"Aa","ui":true}],
          "blocks":[
           {"type":"paragraph","inline":[{"text":"Press "},{"text":"⌘L","kbd":true},{"text":"\n","br":true},{"text":"site","link":"https://example.org"}]},
           {"type":"paragraph","inline":[{"text":"fine print"}],"fine":true},
           {"type":"heading","inline":[{"text":"A heading","bold":true}]},
           {"type":"list","items":[[{"text":"one"}],[{"text":"two","code":true}]]},
           {"type":"steps","items":[[{"type":"paragraph","inline":[{"text":"Step"}]}]]},
           {"type":"table","header":[[{"text":"A"}],[{"text":"B"}]],"rows":[[[{"text":"1"}],[{"text":"2","small":true}]]]},
           {"type":"callout","style":"tip","label":"Tip","blocks":[{"type":"list","items":[[{"text":"x"}]]}]},
           {"type":"figure","image":"03-study.png","device":"phone","caption":"Study"},
           {"type":"feature","figure":{"type":"figure","image":"w02-verse.png","device":"watch","caption":"Watch"},"blocks":[],"flip":true},
           {"type":"feature","figure":null,"mock":{"bar":{"leading":"Cancel","title":"Keys","trailing":"Done"},
             "sections":[{"header":"Crossway","rows":[{"text":"API key","style":"field","highlight":true},
               {"text":"Remove Key","style":"destructive","icon":"🗑"},{"text":"CSB","style":"plain","detail":"English","checked":true}],
               "footer":"Free tier."}]},"blocks":[{"type":"heading","inline":[{"text":"Keys"}]}]},
           {"type":"sparkles","whatever":1}
          ]}]}
        """.trimIndent()

        fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                for ((name, bytes) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }

        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    }

    private fun tempDir(): File = Files.createTempDirectory("guide").toFile().also { it.deleteOnExit() }

    @Test fun decodesEveryBlockKind() {
        val guide = UserGuide.decode(SAMPLE)
        assertEquals("Contents", guide.contents)
        val blocks = guide.chapters[0].blocks
        assertEquals(11, blocks.size)

        val paragraph = blocks[0] as Block.Paragraph
        assertFalse(paragraph.fine)
        assertTrue(paragraph.inline[1].kbd)
        assertTrue(paragraph.inline[2].lineBreak)
        assertEquals("\n", paragraph.inline[2].text)
        assertEquals("https://example.org", paragraph.inline[3].link)
        assertTrue((blocks[1] as Block.Paragraph).fine)
        assertTrue((blocks[2] as Block.Heading).inline[0].bold)
        assertTrue((blocks[3] as Block.Bullets).items[1][0].code)
        assertTrue((blocks[4] as Block.Steps).items[0][0] is Block.Paragraph)

        val table = blocks[5] as Block.Table
        assertEquals(2, table.header.size)
        assertTrue(table.rows[0][1][0].small)

        val callout = blocks[6] as Block.Callout
        assertEquals(UserGuide.CalloutStyle.TIP, callout.style)
        assertEquals("Tip", callout.label)
        assertTrue(callout.blocks.first() is Block.Bullets)

        val figure = (blocks[7] as Block.FigureBlock).figure
        assertEquals(UserGuide.Figure("03-study.png", UserGuide.Device.PHONE, "Study"), figure)

        val watch = blocks[8] as Block.Feature
        assertTrue(watch.flip)
        assertNull(watch.mock)
        assertEquals(UserGuide.Device.WATCH, watch.figure?.device)

        val mockFeature = blocks[9] as Block.Feature
        assertFalse(mockFeature.flip)
        assertNull(mockFeature.figure)
        val mock = mockFeature.mock!!
        assertEquals(UserGuide.Mock.Bar("Cancel", "Keys", "Done"), mock.bar)
        val rows = mock.sections[0].rows
        assertEquals(listOf(UserGuide.Mock.Style.FIELD, UserGuide.Mock.Style.DESTRUCTIVE, UserGuide.Mock.Style.PLAIN), rows.map { it.style })
        assertTrue(rows[0].highlight)
        assertEquals("🗑", rows[1].icon)
        assertTrue(rows[2].checked)
        assertEquals("English", rows[2].detail)
        assertEquals("Crossway", mock.sections[0].header)
        assertEquals("Free tier.", mock.sections[0].footer)

        assertEquals("a block kind from a newer guide is skipped, not fatal", Block.Unknown, blocks[10])
        assertTrue(guide.chapters[0].lede[1].ui)
        assertEquals(setOf("01-reader.png", "03-study.png", "w02-verse.png"), guide.imageNames)
    }

    @Test fun refusesANewerSchema() {
        try {
            UserGuide.decode(SAMPLE.replace("\"schema\":1", "\"schema\":99"))
            fail("schema 99 decoded")
        } catch (e: UnsupportedSchemaException) {
            assertEquals(99, e.schema)
        }
    }

    @Test fun aMissingFieldFails() {
        try {
            UserGuide.decode(SAMPLE.replace("\"contents\":\"Contents\",", ""))
            fail("a guide without contents decoded")
        } catch (_: GuideFormatException) {
            Unit
        }
    }

    @Test fun picksTheLanguage() {
        assertEquals("ja", UserGuidePackage.language(listOf("ja")))
        assertEquals("zh-Hans", UserGuidePackage.language(listOf("zh-Hans-CN")))
        assertEquals("pt-BR", UserGuidePackage.language(listOf("pt-PT")))
        assertEquals("de", UserGuidePackage.language(listOf("de_AT")))
        assertEquals("fr", UserGuidePackage.language(listOf("nl", "fr")))
        assertEquals("en", UserGuidePackage.language(listOf("nl")))
        assertEquals("en", UserGuidePackage.language(emptyList()))
        // Every language the app runs in has a guide of its own, under the same tag.
        for (tag in listOf("en") + AppLanguage.SUPPORTED) assertEquals(tag, UserGuidePackage.language(listOf(tag)))
        assertEquals(UserGuidePackage.LANGUAGES.toSet(), (listOf("en") + AppLanguage.SUPPORTED).toSet())
    }

    @Test fun urls() {
        assertEquals(
            "https://github.com/blaineam/Scripture-Alone/releases/download/user-guide/UserGuide-pt-BR.zip",
            UserGuidePackage.packageUrl("pt-BR"),
        )
        assertEquals(
            "https://github.com/blaineam/Scripture-Alone/releases/download/user-guide/UserGuide-ja.pdf",
            UserGuidePackage.pdfUrl("ja"),
        )
    }

    @Test fun unpacksAPackage() {
        val data = zip("guide.json" to SAMPLE.toByteArray(), "images/" to ByteArray(0), "images/01-reader.png" to PNG)
        val dir = File(tempDir(), "en")
        val guide = UserGuidePackage.unpack(data, dir, "ABC")
        assertEquals(1, guide.chapters.size)
        assertTrue(File(dir, "images/01-reader.png").isFile)
        assertEquals(guide, UserGuidePackage.load(dir))
        assertEquals("abc", UserGuidePackage.recordedSha(dir))
        // Unpacking again replaces the copy in place, and leaves no staging directory behind.
        UserGuidePackage.unpack(data, dir, "def")
        assertEquals(guide, UserGuidePackage.load(dir))
        assertEquals("def", UserGuidePackage.recordedSha(dir))
        assertEquals(listOf("en"), dir.parentFile!!.list()!!.toList())
    }

    @Test fun refusesAPathOutOfTheDirectory() {
        for (bad in listOf("images/../../escape.png", "../escape.png", "/etc/passwd", "images/a/b.png", "other.json", "images/..", "images/.hidden", "images\\x.png")) {
            val data = zip("guide.json" to SAMPLE.toByteArray(), bad to byteArrayOf(1))
            val root = tempDir()
            val dir = File(root, "en")
            try {
                UserGuidePackage.unpack(data, dir)
                fail("$bad was accepted")
            } catch (_: GuideFormatException) {
                Unit
            }
            assertFalse("$bad left a directory", dir.exists())
            assertFalse(File(root, "escape.png").exists())
            assertTrue("$bad left files behind", root.list()!!.isEmpty())
        }
        assertTrue(UserGuidePackage.isSafeName("guide.json"))
        assertTrue(UserGuidePackage.isSafeName("images/01-reader.png"))
        assertFalse(UserGuidePackage.isSafeName("images/"))
    }

    @Test fun aFailedPackageLeavesTheHeldCopy() {
        val dir = File(tempDir(), "en")
        UserGuidePackage.unpack(zip("guide.json" to SAMPLE.toByteArray()), dir, "old")
        val newer = zip("guide.json" to SAMPLE.replace("\"schema\":1", "\"schema\":2").toByteArray())
        try {
            UserGuidePackage.unpack(newer, dir, "new")
            fail("schema 2 unpacked")
        } catch (_: UnsupportedSchemaException) {
            Unit
        }
        assertEquals("old", UserGuidePackage.recordedSha(dir))
        try {
            UserGuidePackage.unpack(zip("images/a.png" to PNG), dir)
            fail("a package without guide.json unpacked")
        } catch (_: GuideFormatException) {
            Unit
        }
        assertEquals("old", UserGuidePackage.recordedSha(dir))
    }

    @Test fun verifiesTheSha() {
        val data = "abc".toByteArray()
        val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(sha, UserGuidePackage.sha256(data))
        assertTrue(UserGuidePackage.verify(data, sha.uppercase()))
        assertFalse(UserGuidePackage.verify("abd".toByteArray(), sha))
    }

    @Test fun parsesTheIndex() {
        val index = UserGuidePackage.parseIndex("""{"schema":1,"packages":{"en":{"sha256":"AB12","size":10},"ja":{"sha256":"cd","size":2}}}""")
        assertEquals(UserGuidePackage.Entry("ab12", 10), index["en"])
        assertEquals(2, index.size)
        try {
            UserGuidePackage.parseIndex("""{"schema":2,"packages":{}}""")
            fail("index schema 2 parsed")
        } catch (_: UnsupportedSchemaException) {
            Unit
        }
    }

    /** Android's own edition (`android-en`) when the index lists it; the plain one until then. */
    @Test fun storeReadsAndroidsEdition() {
        val apple = zip("guide.json" to SAMPLE.toByteArray())
        val android = zip("guide.json" to SAMPLE.replace("Welcome", "Android welcome").toByteArray())
        var listsAndroid = false
        val store = UserGuideStore(tempDir()) { url, _ ->
            when (url) {
                UserGuidePackage.INDEX_URL -> buildString {
                    append("""{"schema":1,"packages":{"en":{"sha256":"${UserGuidePackage.sha256(apple)}","size":${apple.size}}""")
                    if (listsAndroid) append(""","android-en":{"sha256":"${UserGuidePackage.sha256(android)}","size":${android.size}}""")
                    append("}}")
                }.toByteArray()
                UserGuidePackage.packageUrl("en") -> apple
                UserGuidePackage.packageUrl("android-en") -> android
                else -> throw IOException("404 $url")
            }
        }
        assertEquals("android-en", UserGuidePackage.edition("en"))
        assertEquals("Welcome", store.refreshBlocking("en")!!.guide.chapters[0].title)
        listsAndroid = true
        assertEquals("Android welcome", store.refreshBlocking("en")!!.guide.chapters[0].title)
        assertEquals("Android welcome", store.held("en")?.guide?.chapters?.get(0)?.title)
    }

    /** The store against a fake network: download, verify, keep; then nothing when current; never a bad download. */
    @Test fun storeRefreshes() {
        val pkg = zip("guide.json" to SAMPLE.toByteArray(), "images/01-reader.png" to PNG)
        var sha = UserGuidePackage.sha256(pkg)
        var body = pkg
        val fetched = mutableListOf<String>()
        val store = UserGuideStore(tempDir()) { url, _ ->
            fetched += url
            when (url) {
                UserGuidePackage.INDEX_URL -> """{"schema":1,"packages":{"en":{"sha256":"$sha","size":${pkg.size}}}}""".toByteArray()
                UserGuidePackage.packageUrl("en") -> body
                else -> throw IOException("404 $url")
            }
        }
        assertNull(store.held("en"))
        val copy = store.refreshBlocking("en")!!
        assertEquals("Welcome", copy.guide.chapters[0].title)
        assertTrue(copy.image("01-reader.png").isFile)
        assertEquals(copy.guide, store.held("en")?.guide)
        assertEquals(2, fetched.size)

        // Current: the index only.
        fetched.clear()
        assertNull(store.refreshBlocking("en"))
        assertEquals(listOf(UserGuidePackage.INDEX_URL), fetched)

        // A new package whose bytes don't match the index: refused, and the held copy kept.
        sha = "00".repeat(32)
        body = zip("guide.json" to SAMPLE.replace("Welcome", "Tampered").toByteArray())
        try {
            store.refreshBlocking("en")
            fail("a download that doesn't match the index was kept")
        } catch (_: GuideFormatException) {
            Unit
        }
        assertEquals("Welcome", store.held("en")?.guide?.chapters?.get(0)?.title)

        // A language the index doesn't have fails; nothing is downloaded.
        try {
            store.refreshBlocking("ko")
            fail("ko refreshed")
        } catch (_: GuideFormatException) {
            Unit
        }
    }

    /**
     * The real packages, when `python3 Tools/build_manual.py --packages` has been run (`dist/manual/`):
     * every one decodes, holds every image it names, has no block this build can't draw, and matches
     * the index beside it.
     */
    private val EDITIONS = setOf("ipad", "mac", "android")

    @Test fun everyBuiltPackageReads() {
        val resources = System.getProperty("scripturealone.resources") ?: return
        val dist = File(resources).parentFile?.parentFile?.let { File(it, "dist/manual") } ?: return
        val zips = dist.listFiles { f -> f.name.endsWith(".zip") }.orEmpty()
        if (zips.isEmpty()) return
        val index = File(dist, "UserGuide-index.json").takeIf { it.isFile }?.let { UserGuidePackage.parseIndex(it.readText()) }
        for (file in zips) {
            val data = file.readBytes()
            val code = file.name.removePrefix("UserGuide-").removeSuffix(".zip")
            // `en` (iPhone), or a device's edition of it: `ipad-en`, `mac-en`, `android-en`.
            val language = code.substringAfter('-').takeIf { code.substringBefore('-') in EDITIONS } ?: code
            assertTrue("${file.name} is a guide language", language in UserGuidePackage.LANGUAGES)
            index?.get(code)?.let { assertTrue("${file.name} matches the index", UserGuidePackage.verify(data, it.sha256)) }
            val dir = File(tempDir(), code)
            val guide = UserGuidePackage.unpack(data, dir)
            assertTrue("${file.name} has its chapters", guide.chapters.size >= 10)
            fun walk(blocks: List<Block>) {
                for (b in blocks) when (b) {
                    Block.Unknown -> fail("${file.name} has a block this build can't draw")
                    is Block.Feature -> walk(b.blocks)
                    is Block.Callout -> walk(b.blocks)
                    is Block.Steps -> b.items.forEach(::walk)
                    else -> Unit
                }
            }
            guide.chapters.forEach { walk(it.blocks) }
            for (name in guide.imageNames) assertTrue("${file.name} is missing $name", File(dir, "images/$name").isFile)
            dir.parentFile?.deleteRecursively()
        }
    }
}
