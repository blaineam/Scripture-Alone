package com.blainemiller.scripturealone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blainemiller.scripturealone.data.BundledDatabase
import com.blainemiller.scripturealone.data.VerseRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Vertical slice: proves the foundation end to end — the database copied in from the iOS
 * resources, stored uncompressed, opened read-only, queried by the shared verse key, and rendered
 * in Compose. The real reader replaces this.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { SliceScreen() } }
    }
}

private data class Verse(val number: Int, val text: String)

@Composable
private fun SliceScreen() {
    val context = LocalContext.current
    var verses by remember { mutableStateOf<List<Verse>>(emptyList()) }

    var ftsProbe by remember { mutableStateOf("…") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            BundledDatabase.withConnection(context, "BSB.sqlite") { db ->
                val range = VerseRef.chapterRange(book = 43, chapter = 3)   // John 3
                verses = db.prepare("SELECT id, text FROM verses WHERE id BETWEEN ? AND ? ORDER BY id").use { st ->
                    st.bindLong(1, range.first.toLong())
                    st.bindLong(2, range.last.toLong())
                    buildList { while (st.step()) add(Verse(VerseRef.fromKey(st.getLong(0).toInt()).verse, st.getText(1))) }
                }
                // FTS5 probe: fails with "no such module: fts5" on the platform SQLite.
                ftsProbe = db.prepare("SELECT count(*) FROM verses_fts WHERE verses_fts MATCH 'shepherd'").use { st ->
                    st.step(); "FTS5 ok — 'shepherd' matches ${st.getLong(0)} verses"
                }
            }
        }
    }

    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            item { Text("John 3", style = MaterialTheme.typography.headlineMedium) }
            item { Text(ftsProbe, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            items(verses) { v -> Text("${v.number}  ${v.text}", Modifier.padding(vertical = 4.dp)) }
        }
    }
}
