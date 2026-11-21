package com.fitsync.app.cloud

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * 纯 Kotlin URL 与 OAuth 回调安全校验策略，供 WebView 路由、JSBridge 与单元测试共用。
 */
object CloudUrlPolicy {
    private val RETURN_PATH_REGEX = Regex("^/pages/[a-z0-9_-]+/index$")
    private val ACTIVITY_ID_REGEX = Regex("^[a-fA-F0-9-]{8,64}$")
    private val OAUTH_HOST_SET = setOf("accounts.google.com", "www.strava.com", "strava.com")

    fun normalizeBaseUrl(rawUrl: String?, allowCleartext: Boolean): String? {
        val candidate = rawUrl?.trim()?.trimEnd('/') ?: return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uri.userInfo != null || uri.rawFragment != null) return null
        if (scheme == "https") return candidate
        if (scheme == "http" && allowCleartext && host.isNotBlank()) return candidate
        return null
    }

    fun isTrustedWebOrigin(rawUrl: String?, trustedBaseUrl: String): Boolean {
        val target = parseHttpUri(rawUrl) ?: return false
        val trusted = parseHttpUri(trustedBaseUrl) ?: return false
        return sameOrigin(target, trusted)
    }

    fun isTrustedApiUrl(rawApiBase: String?, trustedApiBase: String, trustedWebBase: String): Boolean {
        val candidate = parseHttpUri(rawApiBase) ?: return false
        val apiOrigin = parseHttpUri(trustedApiBase)
        val webOrigin = parseHttpUri(trustedWebBase)
        return (apiOrigin != null && sameOrigin(candidate, apiOrigin)) ||
            (webOrigin != null && sameOrigin(candidate, webOrigin))
    }

    fun isOAuthExternalUrl(rawUrl: String?): Boolean {
        val uri = parseHttpUri(rawUrl) ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "https") return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host == "accounts.google.com") return true
        if (host in OAUTH_HOST_SET && (uri.path.orEmpty().startsWith("/oauth"))) return true
        return false
    }

    fun isValidActivityId(rawId: String?): Boolean =
        rawId != null && ACTIVITY_ID_REGEX.matches(rawId.trim())

    /**
     * 将自定义协议 `fitsync-cloud://oauth?...` 安全还原为受信任 H5 回跳 URL。
     */
    fun buildWebReturnUrl(
        baseWebUrl: String,
        callbackUrl: String?,
        timestampMs: Long = System.currentTimeMillis(),
    ): String? {
        val raw = callbackUrl?.trim() ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!"fitsync-cloud".equals(uri.scheme, ignoreCase = true) ||
            !"oauth".equals(uri.host, ignoreCase = true)
        ) {
            return null
        }

        val params = parseQueryParams(uri.rawQuery)
        val rawPath = params["returnPath"]?.trim().orEmpty()
        val returnPath = if (RETURN_PATH_REGEX.matches(rawPath)) rawPath else "/pages/overview/index"
        val cleanBase = baseWebUrl.trimEnd('/')

        val searchParams = linkedMapOf("_oauthTs" to timestampMs.toString())
        listOf("googleTicket", "googleOAuth", "googleOAuthMsg").forEach { key ->
            params[key]?.takeIf { it.isNotEmpty() }?.let { searchParams[key] = it }
        }

        val hashParams = linkedMapOf<String, String>()
        listOf("stravaOAuth", "stravaOAuthMsg").forEach { key ->
            params[key]?.takeIf { it.isNotEmpty() }?.let { hashParams[key] = it }
        }

        val searchQuery = encodeQuery(searchParams)
        val hashQuery = if (hashParams.isEmpty()) "" else "?${encodeQuery(hashParams)}"
        return "$cleanBase/?$searchQuery#$returnPath$hashQuery"
    }

    private fun parseHttpUri(rawUrl: String?): URI? {
        val candidate = rawUrl?.trim() ?: return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) return null
        return uri
    }

    private fun sameOrigin(a: URI, b: URI): Boolean {
        val schemeA = a.scheme?.lowercase(Locale.ROOT) ?: return false
        val schemeB = b.scheme?.lowercase(Locale.ROOT) ?: return false
        val hostA = a.host?.lowercase(Locale.ROOT) ?: return false
        val hostB = b.host?.lowercase(Locale.ROOT) ?: return false
        return schemeA == schemeB && hostA == hostB && effectivePort(a, schemeA) == effectivePort(b, schemeB)
    }

    private fun effectivePort(uri: URI, scheme: String): Int = when {
        uri.port != -1 -> uri.port
        scheme == "https" -> 443
        scheme == "http" -> 80
        else -> -1
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val idx = pair.indexOf('=')
            val rawKey = if (idx >= 0) pair.substring(0, idx) else pair
            val rawVal = if (idx >= 0) pair.substring(idx + 1) else ""
            val key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name())
            val value = URLDecoder.decode(rawVal, StandardCharsets.UTF_8.name())
            result[key] = value
        }
        return result
    }

    private fun encodeQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8.name())}=" +
                URLEncoder.encode(v, StandardCharsets.UTF_8.name())
        }
}
