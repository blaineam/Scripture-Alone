package com.blainemiller.scripturealone.ui.guide

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.blainemiller.scripturealone.ui.reader.ReaderPalette

/**
 * DEBUG builds only: the User Guide, or its first-launch prompt, as the whole activity — the reader
 * is never composed, so no Bible text is on screen. For emulator checks and screenshots:
 *
 *     adb shell am start -S -n <package>/com.blainemiller.scripturealone.MainActivity --ez userGuideOnly true
 *     adb shell am start -S -n <package>/com.blainemiller.scripturealone.MainActivity --ez userGuideWelcomeOnly true
 */
object GuideDebug {
    enum class Mode { GUIDE, WELCOME }

    fun mode(context: Context, intent: Intent?): Mode? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        val extras = intent?.extras ?: return null
        return when {
            extras.getBoolean("userGuideOnly") -> Mode.GUIDE
            extras.getBoolean("userGuideWelcomeOnly") -> Mode.WELCOME
            else -> null
        }
    }
}

@Composable
fun GuideDebugHost(start: GuideDebug.Mode, onDone: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) ReaderPalette.Dark else ReaderPalette.Light
    var mode by rememberSaveable { mutableStateOf(start) }
    val activity = LocalContext.current as? ComponentActivity
    LaunchedEffect(dark) {
        val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    Box(Modifier.fillMaxSize().background(palette.page), contentAlignment = Alignment.Center) {
        when (mode) {
            GuideDebug.Mode.GUIDE -> UserGuideScreen(palette, onDone, Modifier.windowInsetsPadding(WindowInsets.statusBars))
            GuideDebug.Mode.WELCOME -> GuideWelcomeDialog(palette, onRead = { mode = GuideDebug.Mode.GUIDE }, onSkip = onDone)
        }
    }
}
