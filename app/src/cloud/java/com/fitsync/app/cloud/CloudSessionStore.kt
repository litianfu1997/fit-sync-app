package com.fitsync.app.cloud

import android.content.Context
import com.fitsync.app.BuildConfig
import com.fitsync.app.security.SecureSessionStore

/**
 * 云端版凭据与开发期服务器地址管理。
 *
 * <p>Bearer 会话令牌通过 Android Keystore AES-GCM 加密保存，供系统分享接收 FIT 文件时直传云端；
 * 发布构建固定使用编译期配置的 HTTPS 地址，调试构建允许在本地联调时切换开发服务地址。
 */
class CloudSessionStore(context: Context) {
    private val secureStore = SecureSessionStore(context.applicationContext)
    private val debugPrefs = context.applicationContext.getSharedPreferences(DEBUG_PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun saveSession(token: String, apiBaseUrl: String) {
        if (token.isBlank()) {
            clearSession()
            return
        }
        secureStore.putAll(
            mapOf(
                KEY_CLOUD_TOKEN to token.trim(),
                KEY_CLOUD_API_BASE to apiBaseUrl.trim().trimEnd('/'),
            ),
        )
    }

    @Synchronized
    fun clearSession() {
        secureStore.remove(KEY_CLOUD_TOKEN)
        secureStore.remove(KEY_CLOUD_API_BASE)
    }

    fun currentToken(): String? = secureStore.get(KEY_CLOUD_TOKEN)?.takeIf { it.isNotBlank() }

    fun activeWebUrl(): String {
        if (BuildConfig.DEBUG) {
            val override = debugPrefs.getString(KEY_DEBUG_WEB_URL, null)
            CloudUrlPolicy.normalizeBaseUrl(override, allowCleartext = true)?.let { return it }
        }
        return BuildConfig.CLOUD_WEB_URL
    }

    fun activeApiBaseUrl(): String {
        val activeWeb = activeWebUrl()
        if (BuildConfig.DEBUG && activeWeb != BuildConfig.CLOUD_WEB_URL) {
            return "$activeWeb/api"
        }
        return secureStore.get(KEY_CLOUD_API_BASE)?.takeIf {
            CloudUrlPolicy.isTrustedApiUrl(it, BuildConfig.CLOUD_API_BASE_URL, activeWeb)
        } ?: BuildConfig.CLOUD_API_BASE_URL
    }

    fun setDebugWebUrl(rawUrl: String?): Boolean {
        if (!BuildConfig.DEBUG) return false
        val normalized = CloudUrlPolicy.normalizeBaseUrl(rawUrl, allowCleartext = true)
        debugPrefs.edit().apply {
            if (normalized == null) remove(KEY_DEBUG_WEB_URL) else putString(KEY_DEBUG_WEB_URL, normalized)
        }.apply()
        return normalized != null || rawUrl.isNullOrBlank()
    }

    companion object {
        private const val DEBUG_PREFS_NAME = "fitsync_cloud_debug"
        private const val KEY_DEBUG_WEB_URL = "debug_web_url"
        private const val KEY_CLOUD_TOKEN = "cloud_bearer_token"
        private const val KEY_CLOUD_API_BASE = "cloud_api_base"
    }
}
