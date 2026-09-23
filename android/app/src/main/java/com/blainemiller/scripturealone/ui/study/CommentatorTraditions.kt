package com.blainemiller.scripturealone.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.ui.reader.ReaderPalette

/**
 * Where each commentator stands — iOS's `CommentatorTraditionsView`. The same doctrine of
 * salvation runs through all three; baptism and church government are where they part, so a reader
 * knows whose tradition is speaking on those passages.
 *
 * English only, like the commentaries themselves (hidden outside English — docs/localization.md),
 * so the text is inline rather than in string resources.
 */
private data class Commentator(
    val name: String, val tradition: String, val salvation: String,
    val baptism: String, val church: String, val note: String,
)

private val COMMENTATORS = listOf(
    Commentator("John Calvin (1509–1564)", "Reformed", "Reformed", "Infant baptism", "Presbyterian",
        "The fountainhead of the Reformed tradition. Reformed Baptists share his doctrine of salvation, and the 1689 Confession draws heavily on the confessions that follow him; they part with him on baptism."),
    Commentator("John Gill (1697–1771)", "Particular Baptist", "Reformed", "Believer’s baptism", "Congregational (Baptist)",
        "From the same tradition as the 1689 London Baptist Confession. A “high” Calvinist: wary of speaking of a free offer of the gospel to all, and taught eternal justification — both minority views among Reformed Baptists today."),
    Commentator("Jamieson, Fausset & Brown (1871)", "Presbyterian & Anglican", "Reformed-leaning evangelical", "Infant baptism", "Presbyterian / Anglican",
        "Jamieson and Brown were Scottish Presbyterians, Fausset an evangelical Anglican. Conservative, with a high view of Scripture. Brown was postmillennial, which colours some prophetic passages."),
)

@Composable
internal fun CommentatorTraditionsDialog(palette: ReaderPalette, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.page,
        title = { Text("The commentators", color = palette.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(
                    "All three hold the doctrines of grace; they differ chiefly on baptism and church government. " +
                        "Read a commentator on those passages knowing where he stands.",
                    color = palette.secondary, fontSize = 14.sp,
                )
                for (c in COMMENTATORS) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.name, color = palette.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Fact("Tradition", c.tradition, palette)
                        Fact("Salvation", c.salvation, palette)
                        Fact("Baptism", c.baptism, palette)
                        Fact("Church government", c.church, palette)
                        Text(c.note, color = palette.ink, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = palette.accent) } },
    )
}

@Composable
private fun Fact(label: String, value: String, palette: ReaderPalette) {
    Row {
        Text(label, color = palette.secondary, fontSize = 12.sp, modifier = Modifier.width(128.dp))
        Text(value, color = palette.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
