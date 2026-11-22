package com.blainemiller.scripturealone.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.blainemiller.scripturealone.companion.ScriptureLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Scripture Alone on Wear OS — the Apple Watch app: today's verse, favorites and notes (read-only, from
 * the phone), a simple reader and the translation picker. Standalone: the English editions ship in the
 * APK; a locale Bible's edition comes from the phone ([PhoneLink]).
 */
class MainActivity : ComponentActivity() {

    /** A passage to open, from a complication, the tile or a `scripturealone://open?ref=` link. */
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) route(intent)
        // The watch's library reads its editions and the phone's snapshot from storage: never on the
        // main thread, where a slow read at launch is an ANR ("no focused window"). A process that
        // already has it (the tile or the phone link started first) draws at once.
        val bible = MutableStateFlow(WatchBible.ifReady())
        lifecycleScope.launch(Dispatchers.IO) {
            bible.value = WatchBible.get(this@MainActivity)
            PhoneLink.catchUp(this@MainActivity)
        }
        setContent {
            val ready by bible.collectAsStateWithLifecycle()
            ready?.let { WatchApp(it, pendingRoute) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    private fun route(intent: Intent?) {
        val ref = intent?.dataString?.let(ScriptureLink::range)?.storageString
            ?: intent?.getStringExtra(EXTRA_REF)?.takeIf { com.blainemiller.scripturealone.data.VerseRange.parse(it) != null }
            ?: intent?.getStringExtra(EXTRA_ROUTE)
        if (ref != null) pendingRoute.value = if (ref.contains('/') || ref in Routes.SCREENS) ref else Routes.verse(ref)
    }

    companion object {
        /** A verse range storage string, "19023001-19023001". */
        const val EXTRA_REF = "ref"

        /** Debug: a screen to open at launch — `-watchRoute` on the Apple Watch. */
        const val EXTRA_ROUTE = "route"
    }
}
