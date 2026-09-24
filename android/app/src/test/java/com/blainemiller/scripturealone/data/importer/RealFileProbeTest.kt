package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.data.TranslationInfo
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.study.ImportedStudyStore
import com.blainemiller.scripturealone.data.study.JdbcSqlSource
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the importer against a real, user-owned file named by `SA_IMPORT_PROBE`. Skipped otherwise: the
 * file is copyrighted and never enters the repo. Writes a report into `SA_IMPORT_PROBE_OUT` (the
 * temporary directory by default) and prints its first lines. Ported from `RealFileProbeTests.swift`.
 *
 * `SA_IMPORT_PROBE_RED` may name a bundled store to take red letters from.
 */
class RealFileProbeTest {
    @Test fun probe() {
        val path = System.getenv("SA_IMPORT_PROBE")
        assumeTrue("SA_IMPORT_PROBE is not set", !path.isNullOrEmpty())
        val file = File(path!!)
        val out = File(System.getenv("SA_IMPORT_PROBE_OUT") ?: System.getProperty("java.io.tmpdir")).also { it.mkdirs() }
        val started = System.nanoTime()
        val (bible, preview) = ImportFixtures.importer().read(file)
        val seconds = (System.nanoTime() - started) / 1e9
        val report = ImportCoverageReport(bible)
        val quality = report.quality
        val lines = ArrayList<String>()
        lines += "format: ${preview.format} docs: ${preview.documentCount} read in ${"%.1f".format(seconds)}s"
        lines += "identity: ${preview.identity}"
        lines += "books ${report.booksFound.size} chapters ${report.totalChapters} verses ${report.totalVerses}"
        lines += report.summary
        lines += "quality: ${quality.score} continuity ${quality.continuity} clean ${quality.cleanliness}"
        lines += "--- problems"
        lines += report.problems.take(60)
        val study = bible.study
        lines += "--- study: notes ${study.notes.size} articles ${study.articles.size} " +
            "(intros ${study.articles.count { it.kind == ExtractedStudy.ArticleKind.INTRODUCTION }}) images ${study.images.size} " +
            "bytes ${study.images.sumOf { it.data?.size ?: 0 }}"
        for (note in study.orderedNotes.take(4)) lines += "NOTE ${note.start}–${note.end}: ${note.text.take(120)}"
        for (article in study.articles.take(3)) lines += "ARTICLE ${article.kind} ${article.book} ${article.anchor} ${article.title}: ${article.text.take(80)}"
        for (image in study.images.take(3)) lines += "IMAGE ${image.anchor ?: image.book} ${image.caption} ${image.path}"
        lines += "--- samples"
        val samples = listOf(
            Triple(BookID.GENESIS, 1, 1), Triple(BookID.PSALMS, 23, 1), Triple(BookID.PSALMS, 119, 176),
            Triple(BookID.ISAIAH, 53, 5), Triple(BookID.JOHN, 3, 16), Triple(BookID.ROMANS, 8, 28),
            Triple(BookID.REVELATION, 22, 21), Triple(BookID.THIRD_JOHN, 1, 6),
        )
        for ((book, chapter, verse) in samples) {
            lines += "${book.englishName} $chapter:$verse\t${bible.verses[ref(book, chapter, verse)]?.text ?: "<MISSING>"}"
        }
        File(out, "probe.txt").writeText(lines.joinToString("\n"))
        val all = bible.verses.values.sortedBy { it.ref.key }.joinToString("\n") { "${it.ref.book}:${it.ref.chapter}:${it.ref.verse}\t${it.text}" }
        File(out, "verses.tsv").writeText(all)
        for (line in lines.take(40)) println("PROBE $line")

        // The whole path, store included.
        val directory = File(out, "store").also { it.deleteRecursively(); it.mkdirs() }
        var identity = preview.identity
        if (identity.copyright.isBlank()) identity = identity.copy(copyright = "probe")
        val result = ImportFixtures.importer().importBible(file, identity, directory)
        println("PROBE store: ${result.storeFile} — ${result.report.summary}")
        val info = TranslationInfo(identity.id, identity.name, identity.abbreviation, identity.copyright, identity.license)
        JdbcSqlSource(result.storeFile).use { source ->
            val store = ImportedStudyStore.open(source, info) ?: return
            println("PROBE study source: ${store.source.name} (${store.source.shortName}) — ${store.source.attribution}")
            for (entry in store.commentary(ref(BookID.JOHN, 3, 16).key)) println("PROBE JOHN 3:16 ${entry.range}: ${entry.text.take(160)}")
            store.introduction(BookID.GENESIS.number, 1)?.let { println("PROBE GEN INTRO ${it.text.take(200)}") }
            for (image in store.images(BookID.GENESIS.number, 10)) {
                println("PROBE GEN 10 IMAGE ${image.caption} ${store.imageData(image.id)?.size ?: 0} bytes")
            }
        }
    }
}
