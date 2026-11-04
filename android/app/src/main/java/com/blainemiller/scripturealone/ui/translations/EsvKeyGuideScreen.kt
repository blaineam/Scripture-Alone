package com.blainemiller.scripturealone.ui.translations

import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.blainemiller.scripturealone.BuildConfig
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.online.EsvGuideAction
import com.blainemiller.scripturealone.data.online.EsvGuideEvent
import com.blainemiller.scripturealone.data.online.EsvGuideSession
import com.blainemiller.scripturealone.data.online.EsvKeyCheck
import com.blainemiller.scripturealone.data.online.EsvKeyGuideScript
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.SheetColors
import com.blainemiller.scripturealone.ui.study.GlassTextButton
import com.blainemiller.scripturealone.ui.study.SheetTopBar
import com.blainemiller.scripturealone.ui.study.StudyStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/**
 * Walks the reader through getting their own free ESV API key on Crossway's real pages —
 * `ESVKeyGuideView.swift`.
 *
 * The reader does the parts that are theirs: choosing their account's email and password, and
 * Crossway's “I'm not a robot” check. The app dims the page, points at the next thing, agrees to
 * Crossway's terms on their behalf (turning the switch on is their consent), signs them in, fills
 * in and submits the application, and saves the key when it appears. See `ESVKeyGuide.js`.
 *
 * The web view keeps nothing: its cookies and storage are cleared on the way in and the way out, and
 * the page may talk to the app only from Crossway's own origins.
 */
@Composable
internal fun EsvKeyGuideScreen(palette: ReaderPalette, onKey: (String) -> Unit, onClose: () -> Unit) {
    // Shown in the Translations sheet itself, which is sized correctly: a separate dialog window was
    // pushed down by the status bar without being shortened, which left the bottom of the page — and the
    // coach card on it — below the screen.
    EsvKeyGuideContent(palette, onKey, onClose)
}

@Composable
private fun EsvKeyGuideContent(palette: ReaderPalette, onKey: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val surface = SheetColors.surface(palette)
    val supported = remember { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }
    var checking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(StudyStyle.groupedBackground(palette))) {
        SheetTopBar(
            stringResource(R.string.esv_guide_title), palette,
            leading = { GlassTextButton(stringResource(R.string.common_cancel), palette, surface, onClick = onClose) },
        )
        if (!supported) {
            Text(
                stringResource(R.string.esv_guide_unavailable), color = palette.secondary, fontSize = 15.sp,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }
        val dev = remember { EsvGuideDev.base(context) }
        val session = remember { EsvGuideSession(dev?.loginUrl ?: EsvKeyGuideScript.LOGIN_URL, dev.isApi(), dev.isWeb()) }
        var skipped by remember { mutableStateOf(false) }
        var failedLoad by remember { mutableStateOf(false) }
        val webView = remember { WebView(context) }

        fun foundKey(key: String) {
            checking = true
            scope.launch {
                val verdict = withContext(Dispatchers.IO) { EsvKeyCheck.validate(key) }
                checking = false
                if (verdict == false) {
                    webView.evaluateJavascript("window.__saGuide && window.__saGuide.badKey()", null)
                } else {
                    onKey(key)
                    onClose()
                }
            }
        }

        DisposableEffect(webView) {
            wipe()
            val origins = EsvKeyGuideScript.ORIGINS + listOfNotNull(dev?.origin)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)
                // Nothing Crossway sends is kept on disk: no cache while the guide is open (see `onDispose`).
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            // reCAPTCHA's own frame needs its cookies; nothing outlives this screen (see `wipe`).
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            WebViewCompat.addWebMessageListener(webView, EsvKeyGuideScript.BRIDGE, origins) { _, message: WebMessageCompat, _, _, _ ->
                val text = message.data ?: return@addWebMessageListener
                when (val event = session.onMessage(text)) {
                    is EsvGuideEvent.Key -> foundKey(event.value)
                    EsvGuideEvent.Skip -> skipped = true
                    null -> Unit
                }
            }
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val uri = request.url
                    if (allowedPage(uri.scheme, uri.host, dev)) return false
                    // Anything that is not Crossway's own site opens outside the guide.
                    if (uri.scheme == "https" || uri.scheme == "mailto") {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    return true
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) failedLoad = true
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    // The browser's own “Webpage not available” page is not Crossway's: leave it alone.
                    if (failedLoad) {
                        failedLoad = false
                        return
                    }
                    val page = url?.toUri() ?: return
                    if (!allowedPage(page.scheme, page.host, dev)) return
                    val act = {
                        when (val action = session.onPageFinished(url)) {
                            EsvGuideAction.None -> Unit
                            is EsvGuideAction.Load -> view.loadUrl(action.url)
                            is EsvGuideAction.FillLogin ->
                                view.evaluateJavascript(EsvGuideSession.fillLoginScript(action.email, action.password), null)
                        }
                    }
                    // The script goes onto every Crossway page until the reader skips the guide, then
                    // the page's own next step runs once it exists.
                    if (skipped) act() else view.evaluateJavascript(guideSource(context, dev != null)) { act() }
                }
            }
            webView.loadUrl(dev?.startUrl ?: EsvKeyGuideScript.CREATE_APPLICATION_URL)
            onDispose {
                session.clear()
                webView.clearCache(true)
                webView.clearHistory()
                webView.destroy()
                wipe()
            }
        }

        BackHandler { if (webView.canGoBack()) webView.goBack() else onClose() }
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            if (checking) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x66000000)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = palette.accent)
                        Text(stringResource(R.string.esv_guide_checking), color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}

/** No Crossway cookie, storage or cached page outlives the guide. */
private fun wipe() {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
}

/** Only Crossway's own sites load as the page. A debug build's stand-in page is the one exception. */
private fun allowedPage(scheme: String?, host: String?, dev: EsvGuideDev?): Boolean {
    if (host == null) return false
    if (dev != null && host == dev.host) return true
    return scheme == "https" && (host == "esv.org" || host.endsWith(".esv.org"))
}

/** The script with the reader's language in it. */
private fun guideSource(context: Context, dev: Boolean): String {
    val ids = mapOf(
        "skip" to R.string.esv_guide_skip, "emailTitle" to R.string.esv_guide_email_title,
        "emailBody" to R.string.esv_guide_email_body, "passTitle" to R.string.esv_guide_pass_title,
        "passBody" to R.string.esv_guide_pass_body, "signInTitle" to R.string.esv_guide_sign_in_title,
        "tapThis" to R.string.esv_guide_tap_this, "needAccount" to R.string.esv_guide_need_account,
        "fillTitle" to R.string.esv_guide_fill_title, "fillBody" to R.string.esv_guide_fill_body,
        "tosTitle" to R.string.esv_guide_tos_title, "tosBody" to R.string.esv_guide_tos_body,
        "captchaTitle" to R.string.esv_guide_captcha_title, "captchaBody" to R.string.esv_guide_captcha_body,
        "submitTitle" to R.string.esv_guide_submit_title, "submitBody" to R.string.esv_guide_submit_body,
        "verifyTitle" to R.string.esv_guide_verify_title, "verifyBody" to R.string.esv_guide_verify_body,
        "continue" to R.string.esv_guide_continue, "signingTitle" to R.string.esv_guide_signing_title,
        "signingBody" to R.string.esv_guide_signing_body, "keyTitle" to R.string.esv_guide_key_title,
        "keyBody" to R.string.esv_guide_key_body, "badKeyTitle" to R.string.esv_guide_bad_key_title,
        "badKeyBody" to R.string.esv_guide_bad_key_body, "retry" to R.string.esv_guide_retry,
    )
    val script = context.assets.open("ESVKeyGuide.js").bufferedReader().use { it.readText() }
    val words = EsvKeyGuideScript.words { context.getString(ids.getValue(it)) }.toMutableMap()
    // A debug build's stand-in sends the guide's own buttons to its pages, not Crossway's.
    EsvGuideDev.base(context)?.let {
        words["registerURL"] = it.origin + "/account/register/"
        words["createURL"] = it.origin + "/account/create-application/"
    }
    return EsvKeyGuideScript.source(words, script, dev)
}

/**
 * A debug build's local stand-in for Crossway, so the guide can be proven on an emulator with test
 * values and no real account. Never present in a release build: [base] is always null there.
 *
 * Set from a shell (a one-line file in the app's no-backup folder holding the base URL):
 * `adb reverse tcp:8766 tcp:8766`, then
 * `adb push base.txt /data/local/tmp/ && adb shell run-as com.blainemiller.scripturealone sh -c 'cp /data/local/tmp/base.txt no_backup/esv_guide_dev'`
 * where `base.txt` holds `http://localhost:8766`.
 */
internal class EsvGuideDev(val origin: String) {
    private val uri = URI(origin)
    val host: String = uri.host
    val startUrl = "$origin/login/"
    val loginUrl = "$origin/login/"

    companion object {
        fun base(context: Context): EsvGuideDev? {
            if (!BuildConfig.DEBUG) return null
            // In the no-backup folder, so the setting is never carried to another device.
            val base = runCatching { File(context.noBackupFilesDir, "esv_guide_dev").readText().trim() }.getOrNull()
            return base?.takeIf { it.startsWith("http://") }?.let { EsvGuideDev(it.trimEnd('/')) }
        }
    }
}

private fun EsvGuideDev?.isApi(): (String, String) -> Boolean =
    if (this == null) { host, _ -> host == "api.esv.org" } else { host, path -> host == this.host && path.startsWith("/login") }

private fun EsvGuideDev?.isWeb(): (String, String) -> Boolean =
    if (this == null) { host, _ -> host == "www.esv.org" || host == "esv.org" }
    else { host, path -> host == this.host && !path.startsWith("/login") && !path.startsWith("/account/create-application") }
