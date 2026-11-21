package com.fitsync.app.cloud

import android.content.Context
import com.fitsync.app.BuildConfig
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google AdMob 控制器。
 *
 * <p>默认保持关闭：只有同时满足「构建开关 ADMOB_ENABLED=true」、「服务端 /api/v1/config 返回 admob.enabled=true」
 * 且存在有效广告单元 ID 时，才延迟初始化 MobileAds SDK 并展示横幅广告位。
 */
class CloudAdController(private val context: Context) {
    private val initialized = AtomicBoolean(false)
    private val _activeBannerUnitId = MutableStateFlow<String?>(null)
    val activeBannerUnitId: StateFlow<String?> = _activeBannerUnitId.asStateFlow()

    @Volatile
    private var remoteEnabled = false

    @Volatile
    private var remoteBannerUnitId = ""

    fun updateRemoteConfig(configJson: String) {
        val parsed = runCatching { JSONObject(configJson) }.getOrNull() ?: return
        remoteEnabled = parsed.optBoolean("enabled", false)
        remoteBannerUnitId = parsed.optString("bannerAdUnitId", "").trim()
        recomputeState()
    }

    private fun recomputeState() {
        if (!BuildConfig.ADMOB_ENABLED || !remoteEnabled) {
            _activeBannerUnitId.value = null
            return
        }
        val effectiveUnitId = remoteBannerUnitId.ifBlank { BuildConfig.ADMOB_BANNER_UNIT_ID.trim() }
        if (effectiveUnitId.isBlank()) {
            _activeBannerUnitId.value = null
            return
        }
        if (initialized.compareAndSet(false, true)) {
            MobileAds.initialize(context.applicationContext) {}
        }
        _activeBannerUnitId.value = effectiveUnitId
    }
}
