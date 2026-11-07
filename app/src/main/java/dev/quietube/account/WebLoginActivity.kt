package dev.quietube.account

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.quietube.data.Outcome
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Not exported; no JS bridge, file access, cookie result extras or WebView debugging. */
@AndroidEntryPoint
class WebLoginActivity : ComponentActivity() {
    @Inject lateinit var sessions: EncryptedSessionStore
    @Inject lateinit var client: InnerTubeClient
    private var browser: WebView? = null
    private var capturing = false
    private lateinit var status: TextView
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WebView.setWebContentsDebuggingEnabled(false)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 50, 0, 40); setBackgroundColor(Color.rgb(16,18,21)) }
        val close = Button(this).apply { text = "Close / use cookies.txt import"; setOnClickListener { finish() } }
        status = TextView(this).apply { setTextColor(Color.WHITE); setPadding(24,12,24,12); text = "Sign in to Google. If this browser is blocked, close it and import cookies.txt from your desktop browser." }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val padding = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            insets
        }
        layout.addView(close); layout.addView(status)
        setContentView(layout)
        lifecycleScope.launch {
            clearLoginData(this@WebLoginActivity)
            if (isFinishing) return@launch
            val web = WebView(this@WebLoginActivity)
            browser = web
            web.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                userAgentString = InnerTubeClient.USER_AGENT
                allowFileAccess = false; allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
            }
            CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    val host = url.host.orEmpty()
                    return url.scheme != "https" || !(host == "youtube.com" || host.endsWith(".youtube.com") || host == "google.com" || host.endsWith(".google.com"))
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (capturing || Uri.parse(url).host !in setOf("www.youtube.com", "m.youtube.com", "youtube.com")) return
                    val header = CookieManager.getInstance().getCookie("https://www.youtube.com") ?: return
                    val cookies = try { SessionCookies.fromHeader(header) } catch (_: Exception) { return }
                    val expectedRevision = sessions.status.value.revision
                    capturing = true
                    status.text = "Verifying your YouTube session…"
                    lifecycleScope.launch {
                        try {
                            when (val validation = client.validate(cookies)) {
                                is Outcome.Success -> {
                                    if (sessions.status.value.revision == expectedRevision) client.clear()
                                    if (!sessions.saveIfRevision(validation.value,expectedRevision)) {
                                        capturing = false; status.text = "The session changed. Close this browser and try again."
                                        return@launch
                                    }
                                    browser?.stopLoading(); browser?.clearCache(true)
                                    clearLoginData(this@WebLoginActivity)
                                    setResult(Activity.RESULT_OK); finish()
                                }
                                is Outcome.Failure -> { capturing = false; status.text = "Could not verify the YouTube session yet. Finish signing in, or use cookies.txt import." }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { capturing = false; status.text = "Could not securely save this session. Please try cookies.txt import." }
                    }
                }
            }
            layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            web.loadUrl("https://accounts.google.com/ServiceLogin?continue=https://www.youtube.com")
        }
    }
    override fun onDestroy() {
        browser?.let { web ->
            web.stopLoading(); web.clearHistory(); web.clearCache(true)
            (web.parent as? android.view.ViewGroup)?.removeView(web); web.destroy()
        }
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        WebStorage.getInstance().deleteAllData()
        super.onDestroy()
    }
}

internal suspend fun clearLoginData(context: Context) {
    WebStorage.getInstance().deleteAllData()
    WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
    suspendCancellableCoroutine { continuation ->
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            if (continuation.isActive) continuation.resume(Unit)
        }
    }
}
