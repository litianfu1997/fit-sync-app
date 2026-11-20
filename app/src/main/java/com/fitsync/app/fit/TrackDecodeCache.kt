package com.fitsync.app.fit

import kotlin.math.roundToInt

/**
 * 轨迹与曲线采样解码内存缓存（LRU）。
 *
 * 使用标准库 [LinkedHashMap] + 同步锁实现，纯 JVM 单测可直接运行；
 * 避免列表滚动、切页、详情页进入全屏地图时反复 JSON/Base64 反序列化。
 */
object TrackDecodeCache {

    private const val SUMMARY_MAX_ENTRIES = 256
    private const val DENSE_MAX_ENTRIES = 12
    private const val PROFILE_MAX_ENTRIES = 12
    private const val DEFAULT_PREVIEW_MAX_POINTS = 500

    private val summaryCache = createLruMap<String, List<LatLng>>(SUMMARY_MAX_ENTRIES)
    private val denseCache = createLruMap<String, List<LatLng>>(DENSE_MAX_ENTRIES)
    private val profileCache = createLruMap<String, List<ProfileSample>>(PROFILE_MAX_ENTRIES)

    fun decodeSummaryTrack(json: String?): List<LatLng> {
        if (json.isNullOrBlank()) return emptyList()
        synchronized(summaryCache) {
            summaryCache[json]?.let { return it }
        }
        val decoded = TrackCodec.decodeTrack(json)
        synchronized(summaryCache) {
            summaryCache.getOrPut(json) { decoded }
        }
        return decoded
    }

    fun decodeBestTrackCached(
        cacheKey: String,
        denseBase64: String?,
        summaryJson: String?,
    ): List<LatLng> {
        if (denseBase64.isNullOrBlank() && summaryJson.isNullOrBlank()) return emptyList()
        val key = buildString {
            append(cacheKey)
            append('|')
            append(denseBase64?.hashCode() ?: 0)
            append('|')
            append(summaryJson?.hashCode() ?: 0)
        }
        synchronized(denseCache) {
            denseCache[key]?.let { return it }
        }
        val decoded = TrackCodec.decodeBestTrack(denseBase64, summaryJson)
        synchronized(denseCache) {
            denseCache.getOrPut(key) { decoded }
        }
        return decoded
    }

    fun decodeProfileCached(
        cacheKey: String,
        profileJson: String?,
    ): List<ProfileSample> {
        if (profileJson.isNullOrBlank()) return emptyList()
        val key = "$cacheKey|${profileJson.hashCode()}"
        synchronized(profileCache) {
            profileCache[key]?.let { return it }
        }
        val decoded = TrackCodec.decodeProfile(profileJson)
        synchronized(profileCache) {
            profileCache.getOrPut(key) { decoded }
        }
        return decoded
    }

    /**
     * 详情页 180dp 地图卡片预览抽稀：超过 [maxPoints] 时按等距索引下采样并强制保留起点与终点，
     * 避免 osmdroid Polyline 渲染数千个顶点导致卡顿。
     */
    fun downsampleForPreview(
        points: List<LatLng>,
        maxPoints: Int = DEFAULT_PREVIEW_MAX_POINTS,
    ): List<LatLng> {
        if (maxPoints <= 0) return emptyList()
        if (points.size <= maxPoints) return points
        if (maxPoints == 1) return listOf(points.first())
        val result = ArrayList<LatLng>(maxPoints)
        val lastIndex = points.lastIndex
        val step = lastIndex.toDouble() / (maxPoints - 1)
        var prevIndex = -1
        for (i in 0 until maxPoints - 1) {
            val idx = (i * step).roundToInt().coerceIn(0, lastIndex - 1)
            if (idx > prevIndex) {
                result += points[idx]
                prevIndex = idx
            }
        }
        result += points.last()
        return result
    }

    fun clearForTesting() {
        synchronized(summaryCache) { summaryCache.clear() }
        synchronized(denseCache) { denseCache.clear() }
        synchronized(profileCache) { profileCache.clear() }
    }

    private fun <K, V> createLruMap(maxEntries: Int): MutableMap<K, V> =
        object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > maxEntries
        }
}
