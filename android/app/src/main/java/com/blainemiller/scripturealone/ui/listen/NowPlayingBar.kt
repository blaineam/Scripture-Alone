package com.blainemiller.scripturealone.ui.listen

import androidx.compose.ui.semantics.Role
import com.blainemiller.scripturealone.ui.reader.takesTaps
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.listen.AutoScroll
import com.blainemiller.scripturealone.data.listen.ListenSpeed
import com.blainemiller.scripturealone.data.listen.SleepTimer
import com.blainemiller.scripturealone.text.AppText
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.SheetColors
import com.blainemiller.scripturealone.ui.reader.glass

/**
 * The compact glass bar shown while listening — `ScriptureAlone/Listen/NowPlayingBar.swift`: the verse
 * being read and the voice and speed beneath it; previous verse, play/pause, next verse; the speed
 * menu; the options menu (voice, Continue to Next Chapter, sleep timer — its icon a moon while a timer
 * runs); and Stop. A notice, when there is one, sits in a row above with its own dismiss button.
 */
@Composable
fun NowPlayingBar(listen: ListenController, palette: ReaderPalette, modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .glass(palette, RoundedCornerShape(24.dp), lifted = true, opacity = 0.985f)
            // Swallows taps between the controls, so they don't fall through and select a verse.
            .takesTaps()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listen.notice?.let { NoticeRow(it, palette) { listen.notice = null } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    listen.nowPlayingTitle, color = palette.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(status(listen), color = palette.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Transport(listen, palette)
            SpeedMenu(listen, palette)
            OptionsMenu(listen, palette)
            BarButton(Icons.Rounded.Close, stringResource(R.string.listen_stop), palette.secondary, width = 28.dp, iconSize = 20.dp) { listen.stop() }
        }
    }
}

private fun status(listen: ListenController): String = when (val phase = listen.phase) {
    is ListenController.Phase.Preparing -> phase.message
    ListenController.Phase.Paused -> AppText.get(R.string.listen_paused)
    else -> AppText.get(R.string.listen_status_voice_speed, listen.voiceName, ListenSpeed.label(listen.speed))
}

@Composable
private fun NoticeRow(notice: String, palette: ReaderPalette, onDismiss: () -> Unit) {
    val dismiss = stringResource(R.string.listen_dismiss_notice)
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Info, null, tint = palette.secondary, modifier = Modifier.padding(top = 1.dp).size(15.dp))
        Text(notice, color = palette.ink, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(22.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss).semantics { contentDescription = dismiss },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Cancel, null, tint = palette.secondary.copy(alpha = 0.55f), modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun Transport(listen: ListenController, palette: ReaderPalette) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BarButton(Icons.Rounded.FastRewind, stringResource(R.string.listen_previous_verse), palette.ink, width = 30.dp) { listen.previousVerse() }
        if (listen.phase is ListenController.Phase.Preparing) {
            val preparing = stringResource(R.string.listen_preparing)
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = palette.secondary, strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp).semantics { contentDescription = preparing },
                )
            }
        } else {
            val playing = listen.isPlaying
            BarButton(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (playing) R.string.listen_pause else R.string.listen_play),
                palette.ink, width = 34.dp, iconSize = 30.dp,
            ) { listen.togglePlayPause() }
        }
        BarButton(Icons.Rounded.FastForward, stringResource(R.string.listen_next_verse), palette.ink, width = 30.dp) { listen.nextVerse() }
    }
}

@Composable
private fun SpeedMenu(listen: ListenController, palette: ReaderPalette) {
    var open by remember { mutableStateOf(false) }
    val speedDescription = stringResource(R.string.listen_speed_description, ListenSpeed.label(listen.speed))
    Box {
        Box(
            Modifier.heightIn(min = 34.dp).widthIn(min = 38.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = speedDescription },
            contentAlignment = Alignment.Center,
        ) {
            Text(ListenSpeed.label(listen.speed), color = palette.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeader(stringResource(R.string.listen_speed), palette)
            for (step in ListenSpeed.steps) {
                Choice(ListenSpeed.label(step), step == listen.speed, palette) {
                    open = false
                    listen.updateSpeed(step)
                }
            }
        }
    }
}

@Composable
private fun OptionsMenu(listen: ListenController, palette: ReaderPalette) {
    var open by remember { mutableStateOf(false) }
    val timed = listen.sleepTimer != SleepTimer.OFF
    Box {
        BarButton(
            if (timed) Icons.Rounded.Bedtime else Icons.Outlined.Pending, stringResource(R.string.listen_voice_and_options), palette.ink, width = 34.dp, iconSize = 24.dp,
        ) {
            listen.prepareVoices()
            open = true
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 480.dp)) {
            MenuHeader(stringResource(R.string.listen_voice), palette)
            Choice(stringResource(R.string.listen_voice_automatic), listen.voiceId == null, palette) {
                open = false
                listen.updateVoice(null)
            }
            // Read so the menu recomposes when the engine answers with its voices.
            listen.voicesVersion
            for (voice in listen.voices) {
                Choice(voice.title(), voice.id == listen.voiceId, palette) {
                    open = false
                    listen.updateVoice(voice.id)
                }
            }
            if (listen.voices.isEmpty()) {
                Text(
                    stringResource(R.string.listen_voices_pending), color = palette.secondary, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 240.dp),
                )
            }
            HorizontalDivider(color = SheetColors.separator(palette))
            Choice(stringResource(R.string.listen_continue_chapters), listen.continueChapters, palette) {
                listen.updateContinueChapters(!listen.continueChapters)
            }
            HorizontalDivider(color = SheetColors.separator(palette))
            MenuHeader(stringResource(R.string.listen_sleep_timer), palette)
            for (timer in SleepTimer.entries) {
                Choice(timer.title, timer == listen.sleepTimer, palette) {
                    open = false
                    listen.chooseSleepTimer(timer)
                }
            }
        }
    }
}

@Composable
private fun BarButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    width: Dp,
    iconSize: Dp = 24.dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(width = width, height = 34.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
internal fun MenuHeader(title: String, palette: ReaderPalette) {
    Text(
        title.uppercase(), color = palette.secondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
internal fun Choice(title: String, selected: Boolean, palette: ReaderPalette, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, color = palette.ink, fontSize = 15.sp) },
        trailingIcon = { if (selected) Icon(Icons.Rounded.Check, stringResource(R.string.listen_selected), tint = palette.accent) },
        onClick = onClick,
    )
}

/**
 * Listen and Auto-Scroll, between the chapter arrows in the bottom bar — the iPhone toolbar's middle.
 * Auto-Scroll: tap to start or pause; hold for the speed menu (iOS's `Menu` with a primary action).
 * Listen: starts reading from the verse at the top of the screen, and is play/pause once the bar is up.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListenAndScrollControls(
    palette: ReaderPalette,
    autoScrolling: Boolean,
    autoScrollSpeed: Double,
    onToggleAutoScroll: () -> Unit,
    onPickSpeed: (Double) -> Unit,
    listening: Boolean,
    onListen: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val chooseSpeed = stringResource(R.string.listen_choose_scroll_speed)
    val scrollDescription = stringResource(if (autoScrolling) R.string.listen_pause_scrolling else R.string.listen_auto_scroll)
    val listenDescription = stringResource(if (listening) R.string.listen_pause_listening else R.string.listen_listen)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            Box(
                Modifier.size(width = 48.dp, height = 44.dp).clip(RoundedCornerShape(22.dp))
                    .combinedClickable(role = Role.Button, onLongClickLabel = chooseSpeed, onClick = onToggleAutoScroll, onLongClick = { menu = true })
                    .semantics { contentDescription = scrollDescription },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (autoScrolling) Icons.Rounded.PauseCircle else Icons.Outlined.ArrowCircleDown, null,
                    tint = palette.accent, modifier = Modifier.size(28.dp),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuHeader(stringResource(R.string.listen_speed), palette)
                for (speed in AutoScroll.Speed.entries) {
                    Choice(speed.title, speed.pointsPerSecond == autoScrollSpeed, palette) {
                        menu = false
                        onPickSpeed(speed.pointsPerSecond)
                    }
                }
            }
        }
        Box(
            Modifier.size(width = 48.dp, height = 44.dp).clip(RoundedCornerShape(22.dp)).clickable(role = Role.Button, onClick = onListen)
                .semantics { contentDescription = listenDescription },
            contentAlignment = Alignment.Center,
        ) {
            if (listening) {
                // headphones.circle.fill
                Box(Modifier.size(28.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Headphones, null, tint = palette.page, modifier = Modifier.size(17.dp))
                }
            } else {
                Icon(Icons.Rounded.Headphones, null, tint = palette.accent, modifier = Modifier.size(27.dp))
            }
        }
    }
}

/**
 * Runs a Listen start, first asking — once, and only on Android 13 and later — for the notification
 * permission its lock-screen and notification controls post under. Reading starts either way: the
 * answer only decides whether the controls also appear in the shade.
 */
@Composable
fun rememberListenStart(listen: ListenController): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return remember(listen, launcher) {
        { start ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !listen.askedNotifications &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                listen.markAskedNotifications()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            start()
        }
    }
}

