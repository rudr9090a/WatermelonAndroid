package com.hydrogenlabs.watermelon_android

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.hydrogenlabs.watermelon_android.data.storage.AppPreferences
import com.hydrogenlabs.watermelon_android.playback.NativePlayerBridge
import com.hydrogenlabs.watermelon_android.ui.theme.Watermelon_AndroidTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    companion object {
        // Keep this in sync with android.defaultConfig.versionCode.
        const val APP_VERSION_CODE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ forces edge-to-edge, so handle insets explicitly instead of
        // relying on setDecorFitsSystemWindows. Content is padded below the status
        // bar so the dashboard header never sits under the Wi-Fi/battery icons.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            Watermelon_AndroidTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0B0E14))
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    WebWatermelonApp(AppPreferences(this@MainActivity))
                }
            }
        }
    }

}

private fun parseWebUri(value: String?): Uri? {
    if (value.isNullOrBlank()) return null
    return runCatching { Uri.parse(value) }.getOrNull()
}

private fun sameOrigin(first: Uri?, second: Uri?): Boolean {
    if (first == null || second == null) return false
    val scheme = first.scheme ?: return false
    val host = first.host ?: return false
    return scheme.equals(second.scheme, ignoreCase = true) &&
        host.equals(second.host, ignoreCase = true) &&
        first.port == second.port
}

@SuppressLint("SetJavaScriptEnabled")
@androidx.compose.runtime.Composable
private fun WebWatermelonApp(preferences: AppPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var configuredUrl by remember { mutableStateOf<String?>(null) }
    var urlInput by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var error by remember { mutableStateOf("") }
    var compatibility by remember { mutableStateOf<Compatibility>(Compatibility.Loading) }

    val baseUri = remember(configuredUrl) { parseWebUri(configuredUrl) }
    val playbackBridge = remember(configuredUrl) {
        configuredUrl?.let {
            NativePlayerBridge(
                context = context,
                webViewProvider = { webView },
                isAllowedUrl = { url -> sameOrigin(parseWebUri(url), baseUri) },
            )
        }
    }

    LaunchedEffect(Unit) {
        configuredUrl = preferences.serverUrl.first()
        urlInput = configuredUrl.orEmpty()
    }

    LaunchedEffect(configuredUrl) {
        val url = configuredUrl ?: return@LaunchedEffect
        compatibility = Compatibility.Loading
        compatibility = withContext(Dispatchers.IO) { checkCompatibility(url) }
    }

    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }

    if (configuredUrl == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("WATERMELON", style = MaterialTheme.typography.headlineMedium)
            Text("CHROMIUM CLIENT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(32.dp))
            Text("Connect to your Watermelon web server.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(urlInput, { urlInput = it }, Modifier.fillMaxWidth(), label = { Text("Watermelon URL") }, placeholder = { Text("https://music.example.com") }, singleLine = true)
            Spacer(Modifier.height(14.dp))
            Button({
                val clean = urlInput.trim().trimEnd('/')
                if (clean.startsWith("http://") || clean.startsWith("https://")) {
                    scope.launch { preferences.saveServerUrl(clean); configuredUrl = clean }
                    error = ""
                } else error = "Enter a complete http(s) URL."
            }, Modifier.fillMaxWidth()) { Text("OPEN WATERMELON") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }
    } else if (compatibility is Compatibility.Blocked) {
        val blocked = compatibility as Compatibility.Blocked
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("WATERMELON", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            Text("This app version is no longer supported.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Install a newer Android app version to connect to this server.")
            Text("Required version: ${blocked.requiredVersion}", modifier = Modifier.padding(top = 12.dp))
        }
    } else if (compatibility is Compatibility.Failed) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("WATERMELON", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            Text("Could not verify app compatibility.", style = MaterialTheme.typography.titleMedium)
            Text("Connection was blocked for safety. Check the server URL and try again.", modifier = Modifier.padding(top = 8.dp))
        }
    } else if (compatibility is Compatibility.Loading) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Checking app compatibility...", style = MaterialTheme.typography.titleMedium)
        }
    } else {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                WebView(context).apply {
                    webView = this
                    setBackgroundColor(AndroidColor.parseColor("#0B0E14"))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!request.isForMainFrame) return false
                            if (sameOrigin(request.url, baseUri)) return false
                            // Keep the JS playback bridge away from third-party pages
                            // by sending external links to the system browser.
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, request.url)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            view.evaluateJavascript("window.__watermelonNative = true;", null)
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            CookieManager.getInstance().flush()
                            playbackBridge?.requestState()
                            playbackBridge?.requestQueue()
                        }
                    }
                    playbackBridge?.let { addJavascriptInterface(it, "AndroidPlayer") }
                    playbackBridge?.attach()
                    val cookies = CookieManager.getInstance().getCookie(configuredUrl!!).orEmpty()
                    val hasSession = Regex("(?:^|;\\s*)session=[^;]+").containsMatchIn(cookies) &&
                        Regex("(?:^|;\\s*)username=[^;]+").containsMatchIn(cookies)
                    loadUrl(if (hasSession) "${configuredUrl!!}/dashboard" else configuredUrl!!)
                }
            },
            update = { view ->
                if (view.url == null) view.loadUrl(configuredUrl!!)
            },
            onRelease = { view ->
                playbackBridge?.detach()
                view.stopLoading()
                view.destroy()
                webView = null
            },
        )
    }
}

private sealed interface Compatibility {
    data object Loading : Compatibility
    data object Allowed : Compatibility
    data class Blocked(val requiredVersion: Int) : Compatibility
    data object Failed : Compatibility
}

private fun checkCompatibility(baseUrl: String): Compatibility {
    val connection = runCatching {
        URL("${baseUrl.trimEnd('/')}/api/android/version?version=${MainActivity.APP_VERSION_CODE}")
            .openConnection() as HttpURLConnection
    }.getOrElse { return Compatibility.Failed }

    return try {
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("X-Watermelon-Client", "android")
        if (connection.responseCode !in 200..299) return Compatibility.Failed
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val payload = JSONObject(response)
        if (payload.optBoolean("supported", false)) {
            Compatibility.Allowed
        } else {
            Compatibility.Blocked(payload.optInt("last_support_version", Int.MAX_VALUE))
        }
    } catch (_: Exception) {
        Compatibility.Failed
    } finally {
        connection.disconnect()
    }
}
