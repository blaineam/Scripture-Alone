package com.blainemiller.scripturealone.ui.listen

import android.content.Context
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.listen.CarAction
import com.blainemiller.scripturealone.data.listen.CarCatalog
import com.blainemiller.scripturealone.data.listen.CarEntry
import com.blainemiller.scripturealone.data.listen.CarLabels
import com.blainemiller.scripturealone.data.listen.CarPlanRow
import com.blainemiller.scripturealone.data.listen.SpokenReference
import com.blainemiller.scripturealone.data.listen.VoiceQuery
import com.blainemiller.scripturealone.data.plans.PlanPassage
import com.blainemiller.scripturealone.data.plans.PlanServices
import com.blainemiller.scripturealone.data.prefs.ReaderKeys
import com.blainemiller.scripturealone.data.prefs.readerDataStore
import com.blainemiller.scripturealone.data.reference.Passage
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.userdata.BundledUserDatabase
import com.blainemiller.scripturealone.data.userdata.UserDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Android Auto's view of the app: the browse tree it shows ([tree]) and what choosing a row does ([play]) — the
 * counterpart of the iOS `CarPlayTemplates` and `CarPlayPlayer`. Reads what the reader chose (translation, place,
 * favorites) from the files the app keeps, so it works with the phone's screen off and no reader open.
 */
object CarLibrary {
    fun labels(context: Context) = CarLabels(
        listen = context.getString(R.string.car_listen), today = context.getString(R.string.car_today),
        continueReading = context.getString(R.string.car_continue), startAPlan = context.getString(R.string.car_start_plan),
        books = context.getString(R.string.car_books), oldTestament = context.getString(R.string.car_old_testament),
        newTestament = context.getString(R.string.car_new_testament), favorites = context.getString(R.string.car_favorites),
        readingPlans = context.getString(R.string.plans_title), weeklyStudy = context.getString(R.string.car_weekly_study),
        latestMessage = context.getString(R.string.car_latest_message), nothingToday = context.getString(R.string.car_nothing_today),
    )

    /** The translation the reader chose, if it is one this app can read here; else the default. */
    fun translation(context: Context): String = runBlocking {
        val saved = runCatching { context.readerDataStore.data.first()[ReaderKeys.TRANSLATION] }.getOrNull()
        saved?.takeIf { it in BundledTranslations.ids } ?: BundledTranslations.ids.first()
    }

    /** Where the reader left off: the chapter of the saved position, or John 1. */
    fun location(context: Context): ChapterRef = runBlocking {
        val key = runCatching { context.readerDataStore.data.first()[ReaderKeys.POSITION] }.getOrNull() ?: 0
        VerseRef.fromKey(key).takeIf { it.book in 1..66 }?.let { ChapterRef(it.book, it.chapter) } ?: ChapterRef(43, 1)
    }

    /** The reader's favorites, newest first. Read through a short-lived connection, as the widgets do; none when there is no library yet. */
    fun favorites(context: Context): List<VerseRange> {
        val file = File(context.applicationContext.filesDir, "userdata.sqlite")
        if (!file.exists()) return emptyList()
        return runCatching { UserDataStore(BundledUserDatabase(file)).favorites().map { it.range } }.getOrDefault(emptyList())
    }

    fun tree(context: Context, limit: Int = CarCatalog.DEFAULT_LIMIT): CarEntry {
        val store = PlanServices.store(context)
        val plans = store.started.map { CarPlanRow(it.id, it.title, store.todaysReading(it.id)?.display, it.source?.studyCategory != null) }
        return CarCatalog.tree(labels(context), store.todaysReading()?.display, location(context), favorites(context), limit = limit, plans = plans)
    }

    /** Starts reading what [id] names. False when there is nothing to read (no plan, a translation that isn't here). */
    suspend fun play(context: Context, id: String, fromCar: Boolean = true): Boolean {
        val listen = ListenController.get(context)
        listen.startedFromCar = fromCar
        val translation = translation(context)
        return when (val action = CarAction.parse(id) ?: return false) {
            CarAction.Today -> listen.playTodaysReading(PlanServices.store(context), translation)
            CarAction.Continue -> playChapter(context, listen, translation, location(context))
            is CarAction.Chapter -> playChapter(context, listen, translation, action.ref)
            is CarAction.Spoken -> when (val request = SpokenReference.plan(VoiceQuery.decode(action.query))) {
                SpokenReference.Request.Today -> listen.playTodaysReading(PlanServices.store(context), translation)
                SpokenReference.Request.Resume -> playChapter(context, listen, translation, location(context))
                is SpokenReference.Request.Read -> listen.playReading(request.passages.map(PlanPassage::of), translation)
                SpokenReference.Request.Unknown -> false
            }
            is CarAction.Plan -> {
                val reading = PlanServices.store(context).todaysReading(action.planId) ?: return false
                listen.playReading(reading.passages, translation, reading.devotions)
            }
            is CarAction.Study -> playStudy(context, listen, translation, action.planId)
            is CarAction.Favorites -> {
                // A playlist: the one chosen, those after it, then those before it, each named before it is read.
                val all = favorites(context)
                val start = all.indexOf(action.from).coerceAtLeast(0)
                val ordered = (if (all.isEmpty()) listOf(action.from) else all.drop(start) + all.take(start))
                val passages = ordered.mapNotNull { r ->
                    val book = com.blainemiller.scripturealone.data.canon.BookID.of(r.start.book) ?: return@mapNotNull null
                    PlanPassage.of(Passage.of(book, r.start.chapter, r.start.verse, r.end.chapter, r.end.verse))
                }
                listen.playReading(passages, translation, announce = true)
            }
            is CarAction.Range -> {
                val r = action.range
                val passage = Passage.of(com.blainemiller.scripturealone.data.canon.BookID.of(r.start.book) ?: return false,
                    r.start.chapter, r.start.verse, r.end.chapter, r.end.verse)
                listen.playReading(listOf(PlanPassage.of(passage)), translation)
            }
        }
    }

    /** The church's latest weekly study, aloud: fetched when the phone is online, else the last one kept. */
    private suspend fun playStudy(context: Context, listen: ListenController, translation: String, planId: String): Boolean {
        val source = PlanServices.store(context).plan(planId)?.source?.takeIf { it.studyCategory != null } ?: return false
        val cache = PlanServices.studyCache(context)
        val post = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                com.blainemiller.scripturealone.data.plans.PlanImporter().latestStudy(source, java.time.LocalDate.now())
                    ?.also { cache.save(it, source) }
            } catch (e: com.blainemiller.scripturealone.data.plans.PlanFeedException) {
                cache.load(source)
            }
        } ?: return false
        val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { BundledTranslations.source(context, translation).chapter(ChapterRef(43, 1))?.translation }.getOrNull()
        } ?: return false
        return listen.playProse(post.title, post.text, info.id, info.rights, info.language)
    }

    private suspend fun playChapter(context: Context, listen: ListenController, translation: String, ref: ChapterRef): Boolean {
        val book = com.blainemiller.scripturealone.data.canon.BookID.of(ref.book) ?: return false
        // A whole chapter, read on into the next as the reader's Listen does.
        val chapter = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { BundledTranslations.source(context, translation).chapter(ref) }.getOrNull()
        } ?: return false
        if (book.chapterCount < ref.chapter) return false
        listen.playChapter(chapter, 1)
        return true
    }
}
