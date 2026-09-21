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

    LaunchedEffect(Unit) {
        verses = withContext(Dispatchers.IO) {
            val db = BundledDatabase.open(context, "BSB.sqlite")
            val range = VerseRef.chapterRange(book = 43, chapter = 3)   // John 3
            db.rawQuery(
                "SELECT id, text FROM verses WHERE id BETWEEN ? AND ? ORDER BY id",
                arrayOf(range.first.toString(), range.last.toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(Verse(VerseRef.fromKey(c.getInt(0)).verse, c.getString(1)))
                }
            }
        }
    }

    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            item { Text("John 3", style = MaterialTheme.typography.headlineMedium) }
            items(verses) { v -> Text("${v.number}  ${v.text}", Modifier.padding(vertical = 4.dp)) }
        }
    }
}
