package com.fitsync.app.cloud

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.FileProvider
import okhttp3.Request
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 提供给受信任 H5 页面的原生桥接能力（会话同步、FIT 文件原生下载分享、AdMob 开关同步）。
 */
class CloudFitBridge(
    private val activity: Activity,
    private val webViewProvider: () -> WebView?,
    private val sessionStore: CloudSessionStore,
    private val adController: CloudAdController,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun syncSession(token: String?, apiBase: String?) {
        if (!isTrustedCaller()) return
        val safeToken = token?.trim().orEmpty()
        if (safeToken.isEmpty()) {
            sessionStore.clearSession()
            return
        }
        val activeWeb = sessionStore.activeWebUrl()
        val rawApi = apiBase?.trim().orEmpty().ifBlank { sessionStore.activeApiBaseUrl() }
        if (!CloudUrlPolicy.isTrustedApiUrl(rawApi, sessionStore.activeApiBaseUrl(), activeWeb)) {
            return
        }
        sessionStore.saveSession(safeToken, rawApi)
    }

    @JavascriptInterface
    fun clearSession() {
        if (!isTrustedCaller()) return
        sessionStore.clearSession()
    }

    @JavascriptInterface
    fun syncAdConfig(configJson: String?) {
        if (!isTrustedCaller()) return
        adController.updateRemoteConfig(configJson.orEmpty())
    }

    @JavascriptInterface
    fun openExternalUrl(rawUrl: String?) {
        if (!isTrustedCaller()) return
        val normalized = CloudUrlPolicy.normalizeBaseUrl(rawUrl, allowCleartext = false)
            ?: rawUrl?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) }
            ?: return
        mainHandler.post {
            runCatching {
                CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(normalized))
            }
        }
    }

    @JavascriptInterface
    fun downloadFit(activityId: String?, token: String?, apiBase: String?) {
        if (!isTrustedCaller()) return
        val safeId = activityId?.trim().orEmpty()
        val safeToken = token?.trim().orEmpty()
        if (!CloudUrlPolicy.isValidActivityId(safeId) || safeToken.isEmpty()) {
            showToast("无法导出：活动或登录凭证无效")
            return
        }
        val activeWeb = sessionStore.activeWebUrl()
        val targetApi = apiBase?.trim()?.trimEnd('/').orEmpty().ifBlank { sessionStore.activeApiBaseUrl() }
        if (!CloudUrlPolicy.isTrustedApiUrl(targetApi, sessionStore.activeApiBaseUrl(), activeWeb)) {
            showToast("无法导出：接口地址不在白名单内")
            return
        }

        Thread {
            runCatching {
                val request = Request.Builder()
                    .url("$targetApi/v1/activities/$safeId/file")
                    .header("Authorization", "Bearer $safeToken")
                    .get()
                    .build()
                CloudApp.instance.httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("下载失败（HTTP ${response.code}）")
                    }
                    val body = response.body ?: throw IllegalStateException("服务器未返回 FIT 数据")
                    val exportDir = File(activity.cacheDir, "shared_fits").apply {
                        mkdirs()
                        listFiles()?.forEach { old ->
                            if (System.currentTimeMillis() - old.lastModified() > 3_600_000L) {
                                old.delete()
                            }
                        }
                    }
                    val outFile = File(exportDir, "FitSync-$safeId.fit")
                    body.byteStream().use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val shareUri = FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.files",
                        outFile,
                    )
                    mainHandler.post {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, shareUri)
                            putExtra(Intent.EXTRA_SUBJECT, outFile.name)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        activity.startActivity(Intent.createChooser(sendIntent, "导出原始 FIT"))
                    }
                }
            }.onFailure { err ->
                showToast(err.message ?: "FIT 导出失败，请重试")
            }
        }.start()
    }

    private fun isTrustedCaller(): Boolean {
        val currentUrl = readCurrentWebUrl() ?: return false
        return CloudUrlPolicy.isTrustedWebOrigin(currentUrl, sessionStore.activeWebUrl())
    }

    private fun readCurrentWebUrl(): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return webViewProvider()?.url
        }
        var url: String? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            url = webViewProvider()?.url
            latch.countDown()
        }
        runCatching { latch.await(500, TimeUnit.MILLISECONDS) }
        return url
    }

    private fun showToast(text: String) {
        mainHandler.post {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
        }
    }
}
