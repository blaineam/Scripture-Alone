package com.blainemiller.scripturealone.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.blainemiller.scripturealone.companion.ScriptureLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Scripture Alone on Wear OS — the Apple Watch app: today's verse, favorites and notes (read-only, from
 * the phone), a simple reader and the translation picker. Standalone: the editions ship in the APK.
 */
class MainActivity : ComponentActivity() {

    /** A passage to open, from a complication, the tile or a `scripturealone://open?ref=` link. */
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) route(intent)
        val bible = WatchBible.get(this)
        lifecycleScope.launch(Dispatchers.IO) { PhoneLink.catchUp(this@MainActivity) }
        setContent { WatchApp(bible, pendingRoute) }
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
