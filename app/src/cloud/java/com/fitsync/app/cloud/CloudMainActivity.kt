package com.fitsync.app.cloud

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.fitsync.app.BuildConfig
import com.fitsync.app.ui.components.FitCard
import com.fitsync.app.ui.components.FitCardTone
import com.fitsync.app.ui.components.IconBadge
import com.fitsync.app.ui.components.IconBadgeSize
import com.fitsync.app.ui.components.IconBadgeTone
import com.fitsync.app.ui.theme.FitSpacing
import com.fitsync.app.ui.theme.FitSyncTheme
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

class CloudMainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var loadProgress by mutableIntStateOf(0)
    private var loadErrorMessage by mutableStateOf<String?>(null)
    private var activeUrl by mutableStateOf("")

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            pendingFileCallback?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
            pendingFileCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        syncSystemBarAppearance()

        val app = CloudApp.instance
        val baseUrl = app.sessionStore.activeWebUrl()
        val initialCallback = CloudUrlPolicy.buildWebReturnUrl(baseUrl, intent?.dataString)
        activeUrl = initialCallback ?: baseUrl

        setContent {
            FitSyncTheme {
                val activeBannerUnitId by app.adController.activeBannerUnitId.collectAsState()
                var showDebugUrlDialog by remember { mutableStateOf(false) }

                BackHandler {
                    val current = webView
                    if (current != null && current.canGoBack()) {
                        current.goBack()
                    } else {
                        finish()
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding(),
                ) {
                    if (loadProgress in 1..99 && loadErrorMessage == null) {
                        LinearProgressIndicator(
                            progress = { loadProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context -> createWebView(context, app).also { webView = it } },
                            update = { view ->
                                if (activeUrl.isNotBlank() && view.tag != activeUrl) {
                                    view.tag = activeUrl
                                    view.loadUrl(activeUrl)
                                }
                            },
                        )

                        loadErrorMessage?.let { errorText ->
                            CloudLoadErrorOverlay(
                                message = errorText,
                                currentUrl = app.sessionStore.activeWebUrl(),
                                onRetry = {
                                    loadErrorMessage = null
                                    val target = app.sessionStore.activeWebUrl()
                                    activeUrl = target
                                    webView?.tag = target
                                    webView?.loadUrl(target)
                                },
                                onEditDebugUrl = if (BuildConfig.DEBUG) {
                                    { showDebugUrlDialog = true }
                                } else {
                                    null
                                },
                            )
                        }
                    }

                    activeBannerUnitId?.let { adUnitId ->
                        CloudBannerAd(adUnitId = adUnitId)
                    }
                }

                if (BuildConfig.DEBUG && showDebugUrlDialog) {
                    DebugUrlEditorDialog(
                        initialUrl = app.sessionStore.activeWebUrl(),
                        onDismiss = { showDebugUrlDialog = false },
                        onApply = { raw ->
                            if (app.sessionStore.setDebugWebUrl(raw)) {
                                showDebugUrlDialog = false
                                loadErrorMessage = null
                                val nextUrl = app.sessionStore.activeWebUrl()
                                activeUrl = nextUrl
                                webView?.tag = nextUrl
                                webView?.loadUrl(nextUrl)
                            } else {
                                Toast.makeText(this, "请输入有效的 http:// 或 https:// 地址", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        }

        window.decorView.postOnAnimation { reportFullyDrawn() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val app = CloudApp.instance
        val callbackUrl = CloudUrlPolicy.buildWebReturnUrl(app.sessionStore.activeWebUrl(), intent.dataString)
        if (callbackUrl != null) {
            loadErrorMessage = null
            activeUrl = callbackUrl
            webView?.tag = callbackUrl
            webView?.loadUrl(callbackUrl)
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.removeJavascriptInterface("FitSyncNative")
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun syncSystemBarAppearance() {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: android.content.Context, app: CloudApp): WebView {
        val bridge = CloudFitBridge(
            activity = this,
            webViewProvider = { webView },
            sessionStore = app.sessionStore,
            adController = app.adController,
        )
        return WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            CookieManager.getInstance().setAcceptCookie(true)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                allowFileAccess = false
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                setSupportMultipleWindows(true)
                mixedContentMode = if (BuildConfig.DEBUG) {
                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                } else {
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
            }
            addJavascriptInterface(bridge, "FitSyncNative")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    val trustedBase = app.sessionStore.activeWebUrl()
                    if (CloudUrlPolicy.isOAuthExternalUrl(url)) {
                        openCustomTab(url)
                        return true
                    }
                    if (CloudUrlPolicy.isTrustedWebOrigin(url, trustedBase)) {
                        return false
                    }
                    if (url.startsWith("https://", ignoreCase = true) ||
                        (BuildConfig.DEBUG && url.startsWith("http://", ignoreCase = true))
                    ) {
                        openCustomTab(url)
                        return true
                    }
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url != "about:blank") {
                        loadErrorMessage = null
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        loadErrorMessage = "无法连接到 FitSync 云端服务，请检查网络或稍后重试。"
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    val status = errorResponse?.statusCode ?: return
                    if (request?.isForMainFrame == true && status >= 500) {
                        loadErrorMessage = "云端服务暂时响应异常（HTTP $status），请稍后重试。"
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?,
                ) {
                    handler?.cancel()
                    loadErrorMessage = "安全连接验证失败，请确认系统时间正确或稍后重试。"
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    loadProgress = newProgress
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = filePathCallback
                    return runCatching {
                        filePicker.launch(arrayOf("*/*"))
                        true
                    }.getOrElse {
                        pendingFileCallback?.onReceiveValue(null)
                        pendingFileCallback = null
                        Toast.makeText(this@CloudMainActivity, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                        false
                    }
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?,
                ): Boolean {
                    val target = view?.hitTestResult?.extra
                    if (!target.isNullOrBlank()) {
                        openCustomTab(target)
                    }
                    return false
                }
            }
        }
    }

    private fun openCustomTab(url: String) {
        runCatching {
            CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
        }.onFailure {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }
}

@Composable
private fun CloudLoadErrorOverlay(
    message: String,
    currentUrl: String,
    onRetry: () -> Unit,
    onEditDebugUrl: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(FitSpacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FitCard(modifier = Modifier.fillMaxWidth(), tone = FitCardTone.Surface) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(FitSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconBadge(
                    icon = Icons.Default.CloudOff,
                    size = IconBadgeSize.Lg,
                    tone = IconBadgeTone.Warning,
                )
                Text(
                    text = "暂时无法打开云端页面",
                    modifier = Modifier.padding(top = FitSpacing.lg),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = message,
                    modifier = Modifier.padding(top = FitSpacing.sm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (BuildConfig.DEBUG) {
                    Text(
                        text = currentUrl,
                        modifier = Modifier.padding(top = FitSpacing.sm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().padding(top = FitSpacing.xl),
                ) {
                    Text("重新加载")
                }
                if (onEditDebugUrl != null) {
                    OutlinedButton(
                        onClick = onEditDebugUrl,
                        modifier = Modifier.fillMaxWidth().padding(top = FitSpacing.sm),
                    ) {
                        Text("切换调试服务地址")
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugUrlEditorDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(initialUrl) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调试服务地址") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FitSpacing.sm)) {
                Text(
                    text = "仅在 cloudDebug 生效；模拟器可使用 http://10.0.2.2:5273。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onApply(draft) }) {
                Text("应用并加载")
            }
        },
        dismissButton = {
            Row {
                OutlinedButton(onClick = { onApply("") }) {
                    Text("恢复默认")
                }
            }
        },
    )
}

@Composable
private fun CloudBannerAd(adUnitId: String) {
    val widthDp = LocalConfiguration.current.screenWidthDp.coerceAtLeast(320)
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp))
                this.adUnitId = adUnitId
                loadAd(AdRequest.Builder().build())
            }
        },
    )
}
