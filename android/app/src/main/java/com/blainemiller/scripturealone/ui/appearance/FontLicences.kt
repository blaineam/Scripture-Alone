package com.blainemiller.scripturealone.ui.appearance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.ui.notes.PanelGroup
import com.blainemiller.scripturealone.ui.notes.PanelHeader
import com.blainemiller.scripturealone.ui.notes.PanelSeparator
import com.blainemiller.scripturealone.ui.reader.ReaderFontFamily
import com.blainemiller.scripturealone.ui.reader.ReaderPalette

/**
 * The bundled faces and their licences — every one SIL Open Font License 1.1, whose terms ask that
 * the licence travel with the fonts. Each licence file ships in `assets/licenses/` and is shown here
 * in full on a tap.
 */
@Composable
fun FontLicences(palette: ReaderPalette, onBack: () -> Unit) {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        PanelHeader(stringResource(R.string.appearance_font_licences), palette, back = true, onLeading = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = nav + 24.dp)) {
            SectionFooter(
                stringResource(R.string.appearance_licences_intro),
                palette,
            )
            for (font in FONT_CREDITS) {
                PanelGroup(palette, Modifier.padding(top = 16.dp)) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(font.name, color = palette.ink, fontSize = 20.sp, fontFamily = font.family.fontFamily(20f))
                        Text(stringResource(R.string.appearance_licences_in_place_of, font.family.iosTitle), color = palette.secondary, fontSize = 13.sp)
                        Text(font.credit, color = palette.ink, fontSize = 13.sp, lineHeight = 18.sp)
                        Text(font.licence, color = palette.secondary, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    PanelSeparator(palette)
                    val shown = open == font.file
                    Text(
                        stringResource(if (shown) R.string.appearance_licences_hide else R.string.appearance_licences_show),
                        color = palette.accent, fontSize = 17.sp,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { open = if (shown) null else font.file }
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                    if (shown) {
                        LicenceText(font.file, palette)
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenceText(file: String, palette: ReaderPalette) {
    val context = LocalContext.current
    val text = remember(file) {
        runCatching { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }.getOrDefault("")
    }
    Text(
        text.trim(), color = palette.secondary, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal, modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 14.dp),
    )
}

/** One bundled face: who made it, how it may be used, and where its licence is. */
data class FontCredit(val family: ReaderFontFamily, val name: String, val credit: String, val licence: String, val file: String)

val FONT_CREDITS = listOf(
    FontCredit(
        ReaderFontFamily.NEW_YORK, "Source Serif 4", "© 2014 The Source Serif 4 Project Authors (Adobe).",
        "SIL Open Font License 1.1.", "SourceSerif4-OFL.txt",
    ),
    FontCredit(
        ReaderFontFamily.SAN_FRANCISCO, "Inter", "© 2020 The Inter Project Authors (Rasmus Andersson).",
        "SIL Open Font License 1.1. Subset to Latin, Greek and Cyrillic.", "Inter-OFL.txt",
    ),
    FontCredit(
        ReaderFontFamily.CHARTER, "Charis SIL", "© 1997–2022 SIL International. A derivative of Bitstream Charter.",
        "SIL Open Font License 1.1, with Reserved Font Names “Charis” and “SIL”. Included unmodified.", "CharisSIL-OFL.txt",
    ),
    FontCredit(
        ReaderFontFamily.IOWAN, "Literata", "© 2017 The Literata Project Authors (TypeTogether).",
        "SIL Open Font License 1.1. Subset to Latin, Greek and Cyrillic.", "Literata-OFL.txt",
    ),
    FontCredit(
        ReaderFontFamily.GEORGIA, "Gelasio", "© 2022 The Gelasio Project Authors (Eben Sorkin).",
        "SIL Open Font License 1.1. Subset to Latin, Greek and Cyrillic.", "Gelasio-OFL.txt",
    ),
    FontCredit(
        ReaderFontFamily.PALATINO, "Domitian", "© 2014, 2015 (URW)++ Design & Development; © 2019–2020 Daniel Benjamin Miller. " +
            "Based on Hermann Zapf’s Palatino, as URW Palladio.",
        "Used under the SIL Open Font License 1.1 (offered alongside the AGPL and LPPL). Subset to Latin, Greek and Cyrillic.",
        "Domitian-OFL.txt",
    ),
    FontCredit(
        ReaderFontFamily.AVENIR, "Nunito Sans", "© 2016 The Nunito Sans Project Authors (Vernon Adams, Jacques Le Bailly).",
        "SIL Open Font License 1.1. Subset to Latin, Greek and Cyrillic.", "NunitoSans-OFL.txt",
    ),
)
