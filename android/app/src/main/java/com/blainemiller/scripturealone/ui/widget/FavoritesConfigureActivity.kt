package com.blainemiller.scripturealone.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.blainemiller.scripturealone.companion.VersePalette
import kotlinx.coroutines.launch

/**
 * The Favorites & Notes widget's one setting — "Show" in the iOS widget's edit sheet: everything, or
 * only favorites, highlights or notes. Shown when the widget is placed (it can be skipped: the default
 * is everything) and from the launcher's "reconfigure".
 */
class FavoritesConfigureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves a newly placed widget unplaced, as Android expects.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)

        setContent {
            val scope = rememberCoroutineScope()
            var selected by remember { mutableStateOf<VerseSource?>(null) }
            LaunchedEffect(Unit) {
                selected = VerseSource.of(getAppWidgetState<Preferences>(this@FavoritesConfigureActivity, PreferencesGlanceStateDefinition, glanceId)[VerseSource.KEY])
            }
            SourcePicker(selected) { source ->
                selected = source
                scope.launch {
                    updateAppWidgetState(this@FavoritesConfigureActivity, PreferencesGlanceStateDefinition, glanceId) {
                        it.toMutablePreferences().apply { this[VerseSource.KEY] = source.raw }
                    }
                    FavoritesWidget().update(this@FavoritesConfigureActivity, glanceId)
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                    finish()
                }
            }
        }
    }
}

@Composable
private fun SourcePicker(selected: VerseSource?, onPick: (VerseSource) -> Unit) {
    val palette = if (isSystemInDarkTheme()) VersePalette.DARK else VersePalette.LIGHT
    val ink = Color(palette.ink)
    val accent = Color(palette.accent)
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(palette.pageTop), Color(palette.pageBottom))))
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Favorites & Notes", color = ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Rotates through the verses you’ve favorited, highlighted or written notes on.",
            color = Color(palette.secondaryInk), fontSize = 15.sp,
        )
        Spacer(Modifier.height(20.dp))
        Text("SHOW", color = Color(palette.secondaryInk), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.selectableGroup()) {
            VerseSource.entries.forEach { source ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.RadioButton) { onPick(source) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = source == selected, onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = accent, unselectedColor = Color(palette.secondaryInk)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(source.title, color = ink, fontSize = 17.sp)
                }
            }
        }
    }
}
