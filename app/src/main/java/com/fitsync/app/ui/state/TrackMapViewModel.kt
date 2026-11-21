package com.fitsync.app.ui.state

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.fit.LatLng
import com.fitsync.app.fit.TrackDecodeCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@Immutable
data class TrackMapUiState(
    val isLoading: Boolean = true,
    val activity: CanonicalActivityEntity? = null,
    val points: List<LatLng> = emptyList(),
)

/**
 * 全屏轨迹地图 ViewModel（绑定地图页 NavBackStackEntry）：
 * 后台线程结合 [TrackDecodeCache] 解码轨迹，从详情页进入时直接命中缓存。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackMapViewModel(
    db: FitSyncDatabase,
    private val activityId: Long,
) : ViewModel() {

    val state: StateFlow<TrackMapUiState> = combine(
        db.canonicalActivityDao().observeById(activityId),
        db.activityVariantDao().observeByCanonical(activityId),
    ) { activity, variants ->
        activity to variants
    }.mapLatest { (activity, variants) ->
        withContext(Dispatchers.Default) {
            val primary = variants.firstOrNull { it.id == activity?.primaryVariantId }
                ?: variants.firstOrNull()
            val denseTrack = primary?.denseTrackBase64?.takeIf(String::isNotBlank)
                ?: variants.firstNotNullOfOrNull { it.denseTrackBase64?.takeIf(String::isNotBlank) }
            val trackJson = primary?.trackSummaryJson
            val variantKey = primary?.id ?: 0L
            val points = TrackDecodeCache.decodeBestTrackCached(
                cacheKey = "act-$activityId-$variantKey",
                denseBase64 = denseTrack,
                summaryJson = trackJson,
            )
            TrackMapUiState(
                isLoading = false,
                activity = activity,
                points = points,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(30_000),
        initialValue = TrackMapUiState(isLoading = true),
    )

    companion object {
        fun factory(
            db: FitSyncDatabase,
            activityId: Long,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TrackMapViewModel(db, activityId) as T
        }
    }
}
