package com.blainemiller.scripturealone.ui.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.blainemiller.scripturealone.MainActivity

/**
 * "Open in Scripture Alone" on selected text (ACTION_PROCESS_TEXT) and Share › Scripture Alone for
 * text (ACTION_SEND, text/plain) — Android-only. A trampoline: the sending app's task never holds the
 * reader; the text goes on to [MainActivity] in the app's own task, which reads it with
 * `data/share/SharedText` (a reference opens, anything else is searched).
 */
class TextEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.toString()?.takeIf { it.isNotBlank() }
        if (text != null) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_TEXT)
                    // Bounded: a selection can be a whole page, and only its start is ever used.
                    .putExtra(Intent.EXTRA_TEXT, text.take(MAX_LENGTH))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }

    companion object {
        const val MAX_LENGTH = 4_000
    }
}
