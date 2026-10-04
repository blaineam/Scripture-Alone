package com.blainemiller.scripturealone.ui.guide

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.prefs.ReaderKeys
import com.blainemiller.scripturealone.data.prefs.ReaderPrefs
import com.blainemiller.scripturealone.data.prefs.readerDataStore
import com.blainemiller.scripturealone.ui.notes.PanelColors
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * The first-launch prompt that points a new reader (and, once, everyone updating) at the User Guide:
 * shown once per install, never over a link the app was opened with, another sheet, or a test run.
 * The flag is `userGuide.welcomeShown` in the reader's preferences.
 */
object GuideWelcome {
    suspend fun seen(context: Context): Boolean = try {
        context.applicationContext.readerDataStore.data.first()[ReaderKeys.USER_GUIDE_WELCOME] == true
    } catch (_: IOException) {
        // Settings that can't be read: better to say nothing than to ask again and again.
        true
    }

    fun markSeen(context: Context) {
        ReaderPrefs(context.applicationContext.readerDataStore).write { it[ReaderKeys.USER_GUIDE_WELCOME] = true }
    }

    /** The intent extras the debug launch hooks use (MainActivity's development extras). */
    private val developmentExtras = setOf("book", "chapter", "translation", "theme", "notesInSearch", "favoritesInSearch")

    /**
     * Whether a launch by [intent] may offer the prompt: a plain launch — not a link, a file, a
     * shortcut or a development extra — on a device that isn't running a test harness, a monkey or
     * Firebase Test Lab (Play's pre-launch report).
     */
    fun mayOffer(context: Context, intent: Intent?): Boolean {
        if (intent?.data != null) return false
        val extras = intent?.extras
        if (extras != null && extras.keySet().any { it in developmentExtras }) return false
        if (ActivityManager.isUserAMonkey() || ActivityManager.isRunningInUserTestHarness()) return false
        val testLab = runCatching { Settings.System.getString(context.contentResolver, "firebase.test.lab") }.getOrNull()
        return testLab != "true"
    }
}

/**
 * The prompt itself: a book, "Welcome to Scripture Alone", one sentence, Read the Guide and Skip, and
 * where to find the guide later. Back or a tap outside is Skip.
 */
@Composable
fun GuideWelcomeDialog(palette: ReaderPalette, onRead: () -> Unit, onSkip: () -> Unit) {
    Dialog(onDismissRequest = onSkip, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GuideWelcomeCard(palette, onRead, onSkip)
    }
}

@Composable
fun GuideWelcomeCard(palette: ReaderPalette, onRead: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val accent = palette.accent
    val onAccent = if (palette.isDark) Color(0xFF1D1B18) else Color.White
    Column(
        modifier.padding(24.dp).widthIn(max = 380.dp).fillMaxWidth()
            .clip(RoundedCornerShape(28.dp)).background(PanelColors.card(palette))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = accent, modifier = Modifier.size(38.dp))
        }
        Text(
            stringResource(R.string.user_guide_welcome_title), color = palette.ink, fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = 28.sp,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.user_guide_welcome_message), color = palette.secondary, fontSize = 16.sp,
            textAlign = TextAlign.Center, lineHeight = 22.sp,
        )
        Box(
            Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(min = 50.dp).clip(CircleShape).background(accent)
                .clickable(role = Role.Button, onClick = onRead).padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.user_guide_welcome_read), color = onAccent, fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(CircleShape)
                .clickable(role = Role.Button, onClick = onSkip).padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.user_guide_welcome_skip), color = accent, fontSize = 17.sp, textAlign = TextAlign.Center)
        }
        Text(
            stringResource(R.string.user_guide_welcome_footnote), color = palette.secondary, fontSize = 13.sp,
            textAlign = TextAlign.Center, lineHeight = 18.sp,
        )
    }
}
