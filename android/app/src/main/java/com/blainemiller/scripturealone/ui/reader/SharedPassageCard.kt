package com.blainemiller.scripturealone.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.share.ShareLinkPayload

/**
 * The card a share link carries, shown over the passage it opened — a simple stand-in for iOS's
 * verse-image designer, which opens on the sender's card. Always the Parchment template (its colours
 * from `ShareStyle.swift`), with the words of Christ in the template's red. Tap outside or Done to
 * close; the passage stays selected in the reader beneath.
 */
@Composable
fun SharedPassageCard(payload: ShareLinkPayload, palette: ReaderPalette, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val text = remember(payload) {
        buildAnnotatedString {
            append(payload.text)
            for (range in payload.red) {
                if (range.last < payload.text.length) addStyle(SpanStyle(color = PARCHMENT_RED), range.first, range.last + 1)
            }
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(
                Modifier
                    .padding(horizontal = 28.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .shadow(18.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFFF6EDD9), Color(0xFFEBDDBF))))
                    .clickable(interactionSource = null, indication = null) {}
                    .padding(12.dp)
                    // Parchment has a frame: a fine rule inset from the edge.
                    .border(1.dp, PARCHMENT_ACCENT.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text, color = PARCHMENT_INK, fontSize = 21.sp, lineHeight = 30.sp, textAlign = TextAlign.Center,
                    fontFamily = ReaderTypography.fonts(21f).body,
                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                )
                Box(Modifier.height(1.dp).widthIn(max = 48.dp).fillMaxWidth().background(PARCHMENT_ACCENT.copy(alpha = 0.6f)))
                Text(
                    payload.reference.uppercase(), color = PARCHMENT_ACCENT, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, textAlign = TextAlign.Center,
                )
                if (payload.translation.isNotEmpty()) {
                    Text(payload.translation, color = PARCHMENT_INK.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
            Box(
                Modifier.height(44.dp).glass(palette, CircleShape, lifted = true).clickable(onClick = onDismiss)
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Done", color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private val PARCHMENT_INK = Color(0xFF3B2F20)
private val PARCHMENT_ACCENT = Color(0xFF8A5A2B)
private val PARCHMENT_RED = Color(0xFFA12A1C)
