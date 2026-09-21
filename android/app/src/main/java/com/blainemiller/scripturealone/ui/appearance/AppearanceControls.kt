package com.blainemiller.scripturealone.ui.appearance

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.SheetColors
import kotlin.math.roundToInt

/** iOS's switch green (`systemGreen`), which a `Toggle` in a `Form` draws whatever the tint. */
private val SwitchOn = Color(0xFF34C759)

/**
 * A labelled switch row — SwiftUI's `Toggle` in a `Form`: the title on the left, the capsule switch
 * (51 × 31, a white knob) on the right. The whole row toggles, and TalkBack reads it as a switch.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    palette: ReaderPalette,
    enabled: Boolean = true,
    inset: androidx.compose.ui.unit.Dp = 18.dp,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = inset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, color = if (enabled) palette.ink else palette.secondary.copy(alpha = 0.6f), fontSize = 17.sp,
            modifier = Modifier.weight(1f),
        )
        CapsuleSwitch(checked, palette, enabled)
    }
}

@Composable
private fun CapsuleSwitch(checked: Boolean, palette: ReaderPalette, enabled: Boolean) {
    val progress by animateFloatAsState(if (checked) 1f else 0f, tween(180), label = "switch")
    val track by animateColorAsState(
        if (checked) SwitchOn else SheetColors.buttonFill(palette), tween(180), label = "track",
    )
    Box(
        Modifier.size(width = 51.dp, height = 31.dp).clip(CircleShape).background(track.copy(alpha = if (enabled) track.alpha else track.alpha * 0.5f)),
    ) {
        Box(
            Modifier.padding(2.dp).offset { IntOffset(((20.dp).toPx() * progress).roundToInt(), 0) }
                .size(27.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White),
        )
    }
}

/**
 * A continuous slider — SwiftUI's `Slider`: a thin track filled with the accent up to a white knob.
 * [step] snaps it (the text size moves in whole points); null leaves it continuous (line spacing).
 * TalkBack reads [label] and [valueText] and can set the value.
 */
@Composable
fun SheetSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    palette: ReaderPalette,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    step: Float? = null,
    onChange: (Float) -> Unit,
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val density = LocalDensity.current
    fun snap(v: Float): Float {
        val clamped = v.coerceIn(range)
        return if (step == null) clamped else (Math.round((clamped - range.start) / step) * step + range.start).coerceIn(range)
    }
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier.height(36.dp)
            .semantics {
                contentDescription = label
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(value, range, if (step == null) 0 else ((range.endInclusive - range.start) / step).toInt() - 1)
                setProgress { target ->
                    change(snap(target))
                    true
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // iOS 26's knob is a capsule, wider than it is tall.
        val knob = 38.dp
        val widthPx = with(density) { (maxWidth - knob).toPx() }.coerceAtLeast(1f)
        fun valueAt(x: Float): Float {
            val f = ((x - with(density) { knob.toPx() } / 2) / widthPx).coerceIn(0f, 1f)
            return snap(range.start + f * (range.endInclusive - range.start))
        }
        Box(
            Modifier.fillMaxWidth().height(36.dp)
                .pointerInput(range, step) {
                    detectTapGestures { change(valueAt(it.x)) }
                }
                .pointerInput(range, step) {
                    detectHorizontalDragGestures { pointer, _ ->
                        val next = valueAt(pointer.position.x)
                        if (next != current) change(next)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // Track, then the filled part, then the knob.
            Box(Modifier.padding(horizontal = knob / 2).fillMaxWidth().height(4.dp).clip(CircleShape).background(SheetColors.buttonFill(palette)))
            Box(
                Modifier.padding(start = knob / 2).width(with(density) { (widthPx * fraction).toDp() }).height(4.dp)
                    .clip(CircleShape).background(palette.accent),
            )
            Box(
                Modifier.offset { IntOffset((widthPx * fraction).roundToInt(), 0) }
                    .size(width = knob, height = 24.dp)
                    .shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White),
            )
        }
    }
}
