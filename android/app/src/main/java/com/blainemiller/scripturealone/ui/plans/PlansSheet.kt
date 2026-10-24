package com.blainemiller.scripturealone.ui.plans

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.plans.PlanCatalog
import com.blainemiller.scripturealone.data.plans.PlanFeedException
import com.blainemiller.scripturealone.data.plans.PlanImporter
import com.blainemiller.scripturealone.data.plans.PlanPassage
import com.blainemiller.scripturealone.data.plans.PlanReminders
import com.blainemiller.scripturealone.data.plans.PlanServices
import com.blainemiller.scripturealone.data.plans.PlanSource
import com.blainemiller.scripturealone.data.plans.PlanStore
import com.blainemiller.scripturealone.data.plans.PlanText
import com.blainemiller.scripturealone.data.plans.PlanToday
import com.blainemiller.scripturealone.data.plans.ReadingPlan
import com.blainemiller.scripturealone.data.plans.StudyPost
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.share.AppCommand
import com.blainemiller.scripturealone.text.AppText
import com.blainemiller.scripturealone.ui.listen.ListenController
import com.blainemiller.scripturealone.ui.listen.rememberListenStart
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import com.blainemiller.scripturealone.ui.reader.SheetColors
import com.blainemiller.scripturealone.ui.reader.takesTaps
import com.blainemiller.scripturealone.ui.study.GlassTextButton
import com.blainemiller.scripturealone.ui.study.StudyStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private sealed interface Route {
    data object Home : Route
    data class Start(val planId: String) : Route
    data object Books : Route
    data object Import : Route
    data class Detail(val planId: String) : Route
    data class Study(val source: PlanSource) : Route
}

/**
 * Reading Plans — `PlansView.swift`: Today (read, listen, mark read, streak, catch-up), the plans the
 * reader started, and a way to add one (a year plan, a book, or a church's schedule from its feed).
 * Passages are always read in the reader's own translation; a plan holds only references.
 */
@Composable
fun PlansSheet(reader: ReaderViewModel, palette: ReaderPalette, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PlanServices.store(context) }
    // Any change to the store redraws: a bumped counter read by every screen.
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(store) {
        val l = { tick++ ; PlanReminders.refresh(context) }
        store.addListener(l)
        onDispose { store.removeListener(l) }
    }
    var stack by remember { mutableStateOf<List<Route>>(listOf(Route.Home)) }
    fun push(r: Route) { stack = stack + r }
    fun pop() { stack = stack.dropLast(1) }
    BackHandler(stack.size > 1) { pop() }

    // A followed plan picks up the days published since it was last open — quietly, offline or not.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { PlanImporter().refresh(store, LocalDate.now()) }
    }

    val surface = SheetColors.surface(palette)
    val route = stack.last()
    val title = when (route) {
        Route.Home -> stringResource(R.string.plans_title)
        is Route.Start -> store.plan(route.planId)?.let(PlanText::title).orEmpty()
        Route.Books -> stringResource(R.string.plans_choose_book)
        Route.Import -> stringResource(R.string.plans_import_title)
        is Route.Detail -> store.plan(route.planId)?.let(PlanText::title).orEmpty()
        is Route.Study -> stringResource(R.string.plans_study_title)
    }
    fun openPassage(p: PlanPassage) {
        onClose()
        reader.open(ChapterRef(p.book.number, p.startChapter))
    }
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(surface).takesTaps(),
    ) {
        Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 14.dp)) {
            Box(Modifier.align(Alignment.CenterStart)) {
                if (stack.size > 1) GlassTextButton(stringResource(R.string.common_back), palette, surface, onClick = ::pop)
                else GlassTextButton(stringResource(R.string.common_close), palette, surface, onClick = onClose)
            }
            Text(
                title, color = palette.ink, fontSize = StudyStyle.headline, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp).semantics { heading() },
            )
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(SheetColors.separator(palette)))
        // `tick` is read so a store change recomposes whichever screen is up.
        androidx.compose.runtime.key(tick) {
            when (route) {
                Route.Home -> Home(store, palette, reader, onClose, ::push, ::openPassage)
                is Route.Start -> store.plan(route.planId)?.let { Start(it, store, palette) { stack = listOf(Route.Home) } }
                Route.Books -> Books(palette) { push(Route.Start(PlanCatalog.book(it).id)) }
                Route.Import -> ImportScreen(store, palette) { stack = listOf(Route.Home) }
                is Route.Detail -> Detail(route.planId, store, palette, ::push, ::openPassage) { stack = listOf(Route.Home) }
                is Route.Study -> StudyScreen(route.source, reader, palette)
            }
        }
    }
}

// MARK: Pieces

/** What to tell the reader when an import fails. */
private fun importMessage(e: Throwable): Int = when ((e as? PlanFeedException)?.kind) {
    PlanFeedException.Kind.OFFLINE -> R.string.plans_offline
    PlanFeedException.Kind.NO_READINGS -> R.string.plans_no_readings
    PlanFeedException.Kind.BAD_URL -> R.string.plans_bad_url
    else -> R.string.plans_failed
}

@Composable
private fun SectionTitle(text: String, palette: ReaderPalette) {
    Text(text.uppercase(), color = palette.secondary, fontSize = StudyStyle.footnote, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp).semantics { heading() })
}

@Composable
private fun NavRow(title: String, subtitle: String?, palette: ReaderPalette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.ink, fontSize = StudyStyle.body)
            if (subtitle != null) Text(subtitle, color = palette.secondary, fontSize = StudyStyle.footnote)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = StudyStyle.tertiary(palette))
    }
    Box(Modifier.fillMaxWidth().padding(start = 20.dp).height(0.5.dp).background(StudyStyle.separator(palette)))
}

@Composable
private fun ActionRow(label: String, palette: ReaderPalette, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, tint: Color = palette.accent, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(label, color = tint, fontSize = StudyStyle.body)
    }
    Box(Modifier.fillMaxWidth().padding(start = 20.dp).height(0.5.dp).background(StudyStyle.separator(palette)))
}

@Composable
private fun Note(text: String, palette: ReaderPalette) {
    Text(text, color = palette.secondary, fontSize = StudyStyle.footnote, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

private fun shortDate(d: LocalDate): String = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

// MARK: Home

@Composable
private fun Home(
    store: PlanStore, palette: ReaderPalette, reader: ReaderViewModel, onClose: () -> Unit,
    push: (Route) -> Unit, openPassage: (PlanPassage) -> Unit,
) {
    val reading = store.todaysReading()
    var adding by remember { mutableStateOf(false) }
    var addMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            if (reading != null) {
                val schedule = store.schedule(reading.planId)
                val missed = schedule?.missedDays(store.today())?.size ?: 0
                val streak = schedule?.streak(store.today()) ?: 0
                val readingTitle = store.plan(reading.planId)?.let(PlanText::title) ?: reading.planTitle
                val label = stringResource(R.string.plans_today_label, reading.display, readingTitle, reading.dayNumber, reading.totalDays)
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).semantics(mergeDescendants = true) { contentDescription = label }) {
                    Text(stringResource(if (reading.isCatchUp) R.string.plans_catching_up else R.string.plans_today), color = palette.secondary,
                        fontSize = StudyStyle.caption, fontWeight = FontWeight.SemiBold)
                    reading.day.label?.let { Text(it.uppercase(), color = palette.secondary, fontSize = StudyStyle.caption2) }
                    Text(reading.display, color = palette.ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.plans_day_of, readingTitle, reading.dayNumber, reading.totalDays),
                        color = palette.secondary, fontSize = StudyStyle.footnote)
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(StudyStyle.separator(palette)))
                for (p in reading.passages) ActionRow(stringResource(R.string.plans_read, p.display), palette) { openPassage(p) }
                ActionRow(stringResource(R.string.plans_listen), palette) {
                    onClose()
                    reader.perform(AppCommand.TodaysReading)
                }
                ActionRow(
                    stringResource(if (reading.isDone) R.string.plans_mark_read_undo else R.string.plans_mark_read), palette,
                    if (reading.isDone) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                ) { store.setDone(!reading.isDone, reading.day.index, reading.planId) }
                if (streak > 0) Note(stringResource(R.string.plans_streak, streak), palette)
                if (missed > 0) NavRow(stringResource(R.string.plans_catch_up, missed), null, palette) { push(Route.Detail(reading.planId)) }
                schedule?.plan?.source?.takeIf { it.studyCategory != null }?.let { source ->
                    NavRow(stringResource(R.string.plans_study), null, palette) { push(Route.Study(source)) }
                }
            } else {
                val id = store.primaryId
                val schedule = id?.let(store::schedule)
                if (schedule != null) {
                    val headline = when (val t = schedule.today(store.today())) {
                        PlanToday.Finished -> stringResource(R.string.plans_finished)
                        is PlanToday.NotStarted -> stringResource(R.string.plans_starts, t.starts)
                        else -> stringResource(R.string.plans_no_reading_today)
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text(stringResource(R.string.plans_today), color = palette.secondary, fontSize = StudyStyle.caption, fontWeight = FontWeight.SemiBold)
                        Text(headline, color = palette.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(PlanText.title(schedule.plan), color = palette.secondary, fontSize = StudyStyle.footnote)
                    }
                    schedule.plan.source?.takeIf { it.studyCategory != null }?.let { source ->
                        NavRow(stringResource(R.string.plans_study), null, palette) { push(Route.Study(source)) }
                    }
                }
            }
        }
        if (store.started.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.plans_your_plans), palette) }
            items(store.started, key = { "started-${it.id}" }) { plan ->
                val schedule = store.schedule(plan.id)
                Column(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { push(Route.Detail(plan.id)) }.padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(PlanText.title(plan) + if (plan.id == store.primaryId) "  ★" else "", color = palette.ink, fontSize = StudyStyle.body)
                    if (schedule != null) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { schedule.fraction.toFloat() }, color = palette.accent,
                            trackColor = SheetColors.tertiaryFill(palette), modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.plans_progress, schedule.completedCount, plan.days.size), color = palette.secondary,
                            fontSize = StudyStyle.footnote, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().padding(start = 20.dp).height(0.5.dp).background(StudyStyle.separator(palette)))
            }
        }
        item {
            SectionTitle(stringResource(R.string.plans_add), palette)
            if (store.imported.none { it.source?.siteUrl == PlanImporter.DISCIPLES_CHURCH.site }) {
                NavRow(PlanImporter.DISCIPLES_CHURCH.name, stringResource(R.string.plans_disciples_note), palette) {
                    if (!adding) {
                        adding = true
                        addMessage = null
                        scope.launch {
                            val today = store.today()
                            val result = withContext(Dispatchers.IO) {
                                runCatching { PlanImporter().import("", PlanImporter.DISCIPLES_CHURCH, PlanImporter.Mode.FollowCalendar, today) }
                            }
                            adding = false
                            result.onSuccess { store.add(it.plan); store.start(it.plan, today, true) }
                                .onFailure { addMessage = AppText.get(importMessage(it)) }
                        }
                    }
                }
            }
            if (adding) Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.plans_reading_schedule), color = palette.secondary)
            }
            addMessage?.let { Note(it, palette) }
        }
        items(PlanCatalog.builtIn, key = { "add-${it.id}" }) { plan ->
            NavRow(PlanText.title(plan), PlanText.summary(plan), palette) { push(Route.Start(plan.id)) }
        }
        item {
            NavRow(stringResource(R.string.plans_book_by_book), stringResource(R.string.plans_book_by_book_note), palette) { push(Route.Books) }
            NavRow(stringResource(R.string.plans_import), stringResource(R.string.plans_import_note), palette) { push(Route.Import) }
            Spacer(Modifier.height(48.dp))
        }
    }
}

// MARK: Starting

@Composable
private fun Stepper(label: String, value: String, palette: ReaderPalette, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = palette.ink, fontSize = StudyStyle.body, modifier = Modifier.weight(1f))
        Text("−", color = palette.accent, fontSize = 24.sp, modifier = Modifier.clickable(role = Role.Button, onClick = onMinus).padding(horizontal = 14.dp)
            .semantics { contentDescription = "−" })
        Text(value, color = palette.ink, fontSize = StudyStyle.body)
        Text("+", color = palette.accent, fontSize = 24.sp, modifier = Modifier.clickable(role = Role.Button, onClick = onPlus).padding(horizontal = 14.dp)
            .semantics { contentDescription = "+" })
    }
}

@Composable
private fun Start(plan: ReadingPlan, store: PlanStore, palette: ReaderPalette, finished: () -> Unit) {
    var start by remember { mutableStateOf(store.today()) }
    Column(Modifier.fillMaxSize()) {
        Text(PlanText.summary(plan), color = palette.ink, fontSize = StudyStyle.body, modifier = Modifier.padding(20.dp))
        Stepper(stringResource(R.string.plans_start_date), shortDate(start), palette, { start = start.minusDays(1) }, { start = start.plusDays(1) })
        Note(stringResource(R.string.plans_translation_note), palette)
        Spacer(Modifier.height(12.dp))
        ActionRow(stringResource(R.string.plans_start), palette) {
            store.start(plan, start)
            finished()
        }
    }
}

@Composable
private fun Books(palette: ReaderPalette, onPick: (BookID) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(BookID.entries, key = { it.number }) { book ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(book) }.padding(horizontal = 20.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(book.displayName, color = palette.ink, fontSize = StudyStyle.body, modifier = Modifier.weight(1f))
                Text("${book.chapterCount}", color = palette.secondary, fontSize = StudyStyle.subheadline)
            }
            Box(Modifier.fillMaxWidth().padding(start = 20.dp).height(0.5.dp).background(StudyStyle.separator(palette)))
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

// MARK: Detail

@Composable
private fun Detail(
    planId: String, store: PlanStore, palette: ReaderPalette, push: (Route) -> Unit,
    openPassage: (PlanPassage) -> Unit, stopped: () -> Unit,
) {
    val context = LocalContext.current
    val schedule = store.schedule(planId) ?: return
    val missed = schedule.missedDays(store.today()).map { it.index }.toSet()
    val progress = schedule.progress
    var denied by remember { mutableStateOf(false) }
    var hour by remember { mutableIntStateOf(progress.reminderHour ?: 8) }
    var minute by remember { mutableIntStateOf(progress.reminderMinute ?: 0) }
    val reminderOn = progress.reminderHour != null
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) store.setReminder(planId, hour, minute) else denied = true
    }
    fun turnOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else store.setReminder(planId, hour, minute)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                LinearProgressIndicator(progress = { schedule.fraction.toFloat() }, color = palette.accent, trackColor = SheetColors.tertiaryFill(palette),
                    modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.plans_detail_summary, schedule.completedCount, schedule.plan.days.size, schedule.streak(store.today())),
                    color = palette.secondary, fontSize = StudyStyle.footnote, modifier = Modifier.padding(top = 6.dp))
            }
            if (planId != store.primaryId) ActionRow(stringResource(R.string.plans_primary), palette) { store.makePrimary(planId) }
            SectionTitle(stringResource(R.string.plans_reminder), palette)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.plans_reminder_daily), color = palette.ink, fontSize = StudyStyle.body, modifier = Modifier.weight(1f))
                Switch(checked = reminderOn, onCheckedChange = { on -> if (on) turnOn() else store.setReminder(planId, null, null) })
            }
            if (reminderOn) {
                Stepper(stringResource(R.string.plans_reminder_hour), "%02d".format(hour), palette,
                    { hour = (hour + 23) % 24; store.setReminder(planId, hour, minute) }, { hour = (hour + 1) % 24; store.setReminder(planId, hour, minute) })
                Stepper(stringResource(R.string.plans_reminder_minute), "%02d".format(minute), palette,
                    { minute = (minute + 55) % 60; store.setReminder(planId, hour, minute) }, { minute = (minute + 5) % 60; store.setReminder(planId, hour, minute) })
            }
            if (denied) Note(stringResource(R.string.plans_reminder_denied), palette)
            schedule.plan.source?.let { source ->
                SectionTitle(stringResource(R.string.plans_source), palette)
                ActionRow(source.name, palette) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.siteUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                if (source.studyCategory != null) NavRow(stringResource(R.string.plans_study), null, palette) { push(Route.Study(source)) }
            }
            SectionTitle(stringResource(R.string.plans_days), palette)
        }
        items(schedule.plan.days, key = { it.index }) { day ->
            val done = day.index in progress.completed
            val caption = listOfNotNull(day.label, schedule.dateForDay(day.index)?.let(::shortDate)).joinToString(" · ")
            val description = stringResource(if (done) R.string.plans_day_read else R.string.plans_day_not_read)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clickable(role = Role.Checkbox) { store.setDone(!done, day.index, planId) }
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                        tint = if (done) palette.accent else if (day.index in missed) Color(0xFFE08A1E) else palette.secondary)
                }
                Column(
                    Modifier.weight(1f).clickable(role = Role.Button) { day.passages.firstOrNull()?.let(openPassage) }.padding(vertical = 8.dp),
                ) {
                    Text(day.display, color = palette.ink, fontSize = StudyStyle.body)
                    if (caption.isNotEmpty()) Text(caption, color = palette.secondary, fontSize = StudyStyle.footnote)
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            ActionRow(stringResource(R.string.plans_stop), palette, tint = Color(0xFFD1432F)) {
                store.stop(planId, PlanServices.studyCache(context))
                stopped()
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

// MARK: Import

@Composable
private fun ImportScreen(store: PlanStore, palette: ReaderPalette, finished: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    var since by remember { mutableStateOf(LocalDate.now().minusYears(1)) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun run(preset: PlanImporter.Preset?) {
        working = true
        message = null
        scope.launch {
            val today = store.today()
            val mode = if (follow) PlanImporter.Mode.FollowCalendar else PlanImporter.Mode.FromDayOne(since)
            val result = withContext(Dispatchers.IO) { runCatching { PlanImporter().import(address, preset, mode, today) } }
            working = false
            result.onSuccess {
                store.add(it.plan)
                store.start(it.plan, today, follow)
                finished()
            }.onFailure { e ->
                message = AppText.get(importMessage(e))
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SectionTitle(stringResource(R.string.plans_church), palette)
            NavRow(PlanImporter.DISCIPLES_CHURCH.name, "discipleschurch.com/bible", palette) { if (!working) run(PlanImporter.DISCIPLES_CHURCH) }
            SectionTitle(stringResource(R.string.plans_paste), palette)
            OutlinedTextField(
                value = address, onValueChange = { address = it }, singleLine = true,
                label = { Text(stringResource(R.string.plans_address)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            ActionRow(stringResource(R.string.plans_import_button), palette, tint = if (working || address.isBlank()) palette.secondary else palette.accent) {
                if (!working && address.isNotBlank()) run(null)
            }
            Note(stringResource(R.string.plans_paste_note), palette)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.plans_follow), color = palette.ink, fontSize = StudyStyle.body, modifier = Modifier.weight(1f))
                Switch(checked = follow, onCheckedChange = { follow = it })
            }
            if (!follow) Stepper(stringResource(R.string.plans_since), shortDate(since), palette,
                { since = since.minusMonths(1) }, { if (since.plusMonths(1) <= LocalDate.now()) since = since.plusMonths(1) })
            Note(stringResource(if (follow) R.string.plans_follow_on else R.string.plans_follow_off), palette)
            if (working) Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.plans_reading_schedule), color = palette.secondary)
            }
            message?.let { Note(it, palette) }
        }
    }
}

// MARK: Study

private sealed interface Load {
    data object Loading : Load
    data class Loaded(val post: StudyPost) : Load
    /** No connection: the last study fetched on this device. */
    data class Saved(val post: StudyPost) : Load
    data object None : Load
    data object Offline : Load
    data object Failed : Load
}

/** The site's weekly commentary, fetched when asked for and shown with a link back. Kept on the device for offline reading; never bundled or shared. */
@Composable
private fun StudyScreen(source: PlanSource, reader: ReaderViewModel, palette: ReaderPalette) {
    val context = LocalContext.current
    val cache = remember { PlanServices.studyCache(context) }
    var state by remember { mutableStateOf<Load>(Load.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    val listen = remember { ListenController.get(context) }
    val listenStart = rememberListenStart(listen)
    LaunchedEffect(attempt) {
        state = Load.Loading
        state = withContext(Dispatchers.IO) {
            try {
                val post = PlanImporter().latestStudy(source, LocalDate.now())
                if (post != null) { cache.save(post, source); Load.Loaded(post) } else Load.None
            } catch (e: PlanFeedException) {
                cache.load(source)?.let { Load.Saved(it) } ?: if (e.kind == PlanFeedException.Kind.OFFLINE) Load.Offline else Load.Failed
            }
        }
    }
    @Composable fun Body(post: StudyPost) {
        Text(post.title, color = palette.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.clickable(role = Role.Button) {
                val info = reader.chapter?.translation
                if (info != null) listenStart { listen.playProse(post.title, post.text, info.id, info.rights, info.language) }
            }.padding(vertical = 8.dp),
        ) { Text(stringResource(R.string.plans_read_aloud), color = palette.accent, fontSize = StudyStyle.body) }
        SelectionContainer { Text(post.text, color = palette.ink, fontSize = StudyStyle.body, lineHeight = 24.sp) }
        post.link?.let { link ->
            Text(stringResource(R.string.plans_read_on, source.name), color = palette.accent, fontSize = StudyStyle.footnote,
                modifier = Modifier.clickable(role = Role.Button) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }.padding(vertical = 12.dp))
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
        item {
            when (val s = state) {
                Load.Loading -> CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                is Load.Loaded -> Body(s.post)
                is Load.Saved -> {
                    Text(stringResource(R.string.plans_study_saved), color = palette.secondary, fontSize = StudyStyle.footnote)
                    Spacer(Modifier.height(8.dp))
                    Body(s.post)
                    ActionRow(stringResource(R.string.plans_try_again), palette) { attempt++ }
                }
                Load.None -> Text(stringResource(R.string.plans_study_none), color = palette.secondary)
                Load.Offline, Load.Failed -> {
                    Text(stringResource(if (s == Load.Offline) R.string.plans_study_offline else R.string.plans_study_failed), color = palette.secondary)
                    ActionRow(stringResource(R.string.plans_try_again), palette) { attempt++ }
                }
            }
            if (state !is Load.Loaded && state !is Load.Saved) {
                Text(stringResource(R.string.plans_open_source, source.name), color = palette.accent, fontSize = StudyStyle.footnote,
                    modifier = Modifier.clickable(role = Role.Button) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.siteUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }.padding(vertical = 12.dp))
            }
        }
    }
}
