// An agent's chat opens in T3 Code's Android app (com.t3tools.t3code) at
// t3code://threads/<environment>/<thread>, the link the Mac opens too. Without
// that app it opens here, in T3 Code's web app on the machine that runs it,
// over NetBird: https://<host>.netbird.cloud:3774/<environment>/<thread>
// (docs/prism-api.md, "Open the thread").
//
// Those servers present a certificate from fleet's private CA
// (traits/agent-host/t3-https/ca.crt in fleet, copied to res/raw/t3_ca.crt),
// which may sign *.netbird.cloud names only. The phone's trust store doesn't
// have it, so this WebView checks such certificates itself: issued by that CA,
// currently valid, and naming the host. Everything else gets the normal checks,
// and nothing else is accepted.

package at.quickme.prism

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import at.quickme.prism.shared.Agent
import at.quickme.prism.shared.T3Links
import at.quickme.prism.ui.CodeFamily
import at.quickme.prism.ui.HostBadge
import at.quickme.prism.ui.PrismTheme
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

class T3Activity : ComponentActivity() {
    companion object {
        const val EXTRA_URL = "url"
        const val PORT = 3774
        const val T3_APP = "com.t3tools.t3code"

        /**
         * The intent that opens a thread: in T3 Code's app when it is installed,
         * else in this WebView. Null when the event lacks what it needs.
         */
        @JvmStatic
        fun intent(c: Context, host: String?, environment: String?, thread: String?): Intent? {
            if (host.isNullOrEmpty() || environment.isNullOrEmpty() || thread.isNullOrEmpty() || !host.matches(Regex("[a-z0-9-]+"))) {
                return null
            }
            val link = T3Links.app(environment, thread) ?: return null
            // The managed build of T3 Code takes the same link under a scheme of its own.
            val managed = Intent(Intent.ACTION_VIEW, Uri.parse(link.replaceFirst("t3code://", "t3code-prism://")))
                .setPackage(T3Chat.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (T3Chat.trustedInstall(c) && managed.resolveActivity(c.packageManager) != null) return managed
            val app = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(T3_APP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (app.resolveActivity(c.packageManager) != null) return app
            val url = T3Links.web(host, environment, thread) ?: return null
            return Intent(c, T3Activity::class.java).putExtra(EXTRA_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /** The intent that opens [agent]'s thread, on the machine that runs it; null without one. */
        fun intent(c: Context, agent: Agent): Intent? =
            agent.t3?.let { intent(c, it.host, it.environmentId, it.threadId.ifEmpty { agent.id }) }

        /** Whether the certificate WebView refused is a *.netbird.cloud one from fleet's CA. */
        @JvmStatic
        fun fleetCertificate(c: Context, error: SslError): Boolean {
            if (error.primaryError != SslError.SSL_UNTRUSTED) return false
            val leaf = error.certificate.x509Certificate ?: return false
            val host = Uri.parse(error.url).host ?: return false
            if (!host.endsWith(".netbird.cloud")) return false
            return try {
                val ca = c.resources.openRawResource(R.raw.t3_ca).use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
                }
                if (leaf.issuerX500Principal != ca.subjectX500Principal) return false
                leaf.verify(ca.publicKey)
                leaf.checkValidity()
                ca.checkValidity()
                names(leaf, host)
            } catch (e: Exception) {
                false
            }
        }

        /** The host is one of the certificate's DNS names (one leading wildcard label allowed). */
        @JvmStatic
        fun names(cert: X509Certificate, host: String): Boolean {
            val sans = cert.subjectAlternativeNames ?: return false
            val h = host.lowercase(Locale.ROOT)
            for (san in sans) {
                if ((san[0] as? Int) != 2) continue // dNSName
                val name = san[1].toString().lowercase(Locale.ROOT)
                if (name == h) return true
                if (name.startsWith("*.") && h.endsWith(name.substring(1)) && h.indexOf('.') == h.length - name.length + 1) return true
            }
            return false
        }
    }

    private var web: WebView? = null
    private var loadProgress by mutableStateOf(0)
    private var canGoBack by mutableStateOf(false)
    private var page by mutableStateOf("")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        val uri = url?.let(Uri::parse)
        if (uri == null || uri.scheme != "https" || uri.host?.endsWith(".netbird.cloud") != true) {
            finish()
            return
        }
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    if (target.host?.endsWith(".netbird.cloud") == true) return false
                    // Links out of T3 open in the browser, not in here.
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, target))
                    } catch (e: Exception) {
                        // No app for it.
                    }
                    return true
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    page = url
                    canGoBack = view.canGoBack()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    canGoBack = view.canGoBack()
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    page = url
                    canGoBack = view.canGoBack()
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    if (fleetCertificate(this@T3Activity, error)) handler.proceed() else handler.cancel()
                }
            }
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    loadProgress = newProgress
                }
            }
        }
        web = view
        if (savedInstanceState != null) view.restoreState(savedInstanceState) else view.loadUrl(url)
        page = url
        val host = uri.host!!.removeSuffix(".netbird.cloud")
        setContent {
            PrismTheme {
                // Back steps back inside T3 first; at its first page it leaves (predictive back).
                BackHandler(enabled = canGoBack) { web?.goBack() }
                Scaffold(
                    topBar = {
                        Column {
                            TopAppBar(
                                title = {
                                    Column {
                                        Text("T3 Code", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            Uri.parse(page).path.orEmpty(), maxLines = 1,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFamily),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                navigationIcon = { IconButton(onClick = ::finish) { Icon(Icons.Rounded.Close, "Close") } },
                                actions = {
                                    HostBadge(host, Modifier.padding(end = 4.dp))
                                    IconButton(onClick = { web?.reload() }) { Icon(Icons.Rounded.Refresh, "Reload") }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            )
                            AnimatedVisibility(loadProgress in 1..99) {
                                LinearWavyProgressIndicator(progress = { loadProgress / 100f }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web?.saveState(outState)
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
