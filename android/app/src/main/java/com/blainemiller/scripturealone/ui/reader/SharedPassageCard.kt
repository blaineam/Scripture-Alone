package com.blainemiller.scripturealone.ui.reader

import androidx.compose.ui.semantics.Role
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.share.SharePassageText
import com.blainemiller.scripturealone.data.share.ShareLinkPayload
import com.blainemiller.scripturealone.ui.share.Preview
import com.blainemiller.scripturealone.ui.share.ShareAspect
import com.blainemiller.scripturealone.ui.share.ShareCardContent
import com.blainemiller.scripturealone.ui.share.ShareCardFitter
import com.blainemiller.scripturealone.ui.share.ShareCardMetrics
import com.blainemiller.scripturealone.ui.share.ShareCardRenderer
import com.blainemiller.scripturealone.ui.share.ShareLinkNumbers
import com.blainemiller.scripturealone.ui.share.ShareStyle
import com.blainemiller.scripturealone.ui.share.ShareTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The card a share link carries, shown over the passage it opened: the link's own template, typeface
 * and aspect (`tp`, `f`, `a`; Parchment, Source Serif and Square when a link names none or one this
 * app doesn't know), drawn by the same renderer as the designer, from the link's own text — so it is
 * the card the sender made. Verse numbers are found in the text from `k`, as the web page finds them.
 *
 * iOS opens its designer on the link instead, rebuilt from its own copy of the translation. Here
 * **Make Image…** does that ([onDesign]); tap outside or Done to close, and the passage stays selected
 * in the reader beneath.
 */
@Composable
fun SharedPassageCard(
    payload: ShareLinkPayload,
    palette: ReaderPalette,
    onDismiss: () -> Unit,
    onDesign: (() -> Unit)? = null,
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val renderer = remember { ShareCardRenderer(context.applicationContext) }
    val style = remember(payload) { linkStyle(payload) }
    var content by remember(payload) { mutableStateOf<ShareCardContent?>(null) }
    LaunchedEffect(payload) {
        content = withContext(Dispatchers.Default) { linkContent(payload, style, renderer) }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .takesTaps(onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.padding(horizontal = 24.dp).widthIn(max = 520.dp)) {
            val cardHeight = (maxHeight.value - 120f).coerceAtLeast(200f)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.takesTaps()) {
                    Preview(renderer, content, style, maxHeight = cardHeight, reference = payload.reference)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (onDesign != null) CardButton("Make Image…", palette, onDesign)
                    CardButton("Done", palette, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun CardButton(title: String, palette: ReaderPalette, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp).glass(palette, CircleShape, lifted = true).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The link's look: its template, typeface and aspect, centred, with red letters as the link carries them. */
internal fun linkStyle(payload: ShareLinkPayload) = ShareStyle(
    template = ShareTemplate.fromRaw(payload.template) ?: ShareTemplate.PARCHMENT,
    aspect = ShareAspect.fromRaw(payload.aspect) ?: ShareAspect.SQUARE,
    family = ReaderFontFamily.fromShareToken(payload.font) ?: ReaderFontFamily.DEFAULT,
)

/** The link's text at the largest size that fits its card; the smallest if none does (the card shrinks it further). */
private fun linkContent(payload: ShareLinkPayload, style: ShareStyle, renderer: ShareCardRenderer): ShareCardContent {
    val passage = SharePassageText(payload.text, payload.red, ShareLinkNumbers.find(payload.text, payload.ranges))
    val metrics = ShareCardMetrics(style.aspect)
    val size = ShareCardFitter.largestFittingSize(metrics, metrics.textHeight(footer = style.wordmark)) {
        renderer.passageHeight(passage, it, style)
    } ?: metrics.minFontSize
    return ShareCardContent(passage, payload.reference, payload.translation, notice = null, fontSize = size)
}
