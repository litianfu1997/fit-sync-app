package com.fitsync.app.ui.state

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.db.entity.DeliveryJobEntity
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.data.db.entity.PlatformCopyEntity
import com.fitsync.app.domain.model.DedupStatus
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
data class ActivityDetailUiState(
    val isLoading: Boolean = true,
    val activity: CanonicalActivityEntity? = null,
    val variants: List<ActivityVariantEntity> = emptyList(),
    val copies: List<PlatformCopyEntity> = emptyList(),
    val jobs: List<DeliveryJobEntity> = emptyList(),
    val accounts: List<PlatformAccountEntity> = emptyList(),
    val reviewCandidate: CanonicalActivityEntity? = null,
    val previewTrack: List<LatLng> = emptyList(),
    val speedSeries: List<Float> = emptyList(),
    val altitudeSeries: List<Float> = emptyList(),
)

/**
 * 活动详情页 ViewModel（绑定详情页 NavBackStackEntry）：
 * 将高密度轨迹、速度/海拔曲线解码以及预览点下采样全部放在后台线程执行，
 * 并通过 [TrackDecodeCache] 与全屏地图共享解码结果。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityDetailViewModel(
    db: FitSyncDatabase,
    private val activityId: Long,
) : ViewModel() {

    val state: StateFlow<ActivityDetailUiState> = combine(
        db.canonicalActivityDao().observeById(activityId),
        db.activityVariantDao().observeByCanonical(activityId),
        db.platformCopyDao().observeByCanonical(activityId),
        db.deliveryJobDao().observeByCanonical(activityId),
        db.platformAccountDao().observeAll(),
    ) { activity, variants, copies, jobs, accounts ->
        DetailRawBundle(activity, variants, copies, jobs, accounts)
    }.mapLatest { bundle ->
        withContext(Dispatchers.Default) {
            val activity = bundle.activity
            val variants = bundle.variants
            val primary = variants.firstOrNull { it.id == activity?.primaryVariantId }
                ?: variants.firstOrNull()
            val denseTrack = primary?.denseTrackBase64?.takeIf(String::isNotBlank)
                ?: variants.firstNotNullOfOrNull { it.denseTrackBase64?.takeIf(String::isNotBlank) }
            val trackJson = primary?.trackSummaryJson
            val variantKey = primary?.id ?: 0L
            val fullTrack = TrackDecodeCache.decodeBestTrackCached(
                cacheKey = "act-$activityId-$variantKey",
                denseBase64 = denseTrack,
                summaryJson = trackJson,
            )
            val previewTrack = TrackDecodeCache.downsampleForPreview(fullTrack, PREVIEW_MAX_POINTS)
            val profile = TrackDecodeCache.decodeProfileCached(
                cacheKey = "prof-$activityId-$variantKey",
                profileJson = primary?.profileJson,
            )
            val speedSeries = profile.mapNotNull { it.speedMps?.toFloat() }
            val altitudeSeries = profile.mapNotNull { it.altitudeMeters?.toFloat() }
            val candidateId = activity?.reviewCandidateCanonicalId
            val reviewCandidate = if (
                activity?.dedupStatus == DedupStatus.NEEDS_REVIEW && candidateId != null
            ) {
                withContext(Dispatchers.IO) {
                    db.canonicalActivityDao().getById(candidateId)
                }
            } else {
                null
            }
            ActivityDetailUiState(
                isLoading = false,
                activity = activity,
                variants = variants,
                copies = bundle.copies,
                jobs = bundle.jobs,
                accounts = bundle.accounts,
                reviewCandidate = reviewCandidate,
                previewTrack = previewTrack,
                speedSeries = speedSeries,
                altitudeSeries = altitudeSeries,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(30_000),
        initialValue = ActivityDetailUiState(isLoading = true),
    )

    private data class DetailRawBundle(
        val activity: CanonicalActivityEntity?,
        val variants: List<ActivityVariantEntity>,
        val copies: List<PlatformCopyEntity>,
        val jobs: List<DeliveryJobEntity>,
        val accounts: List<PlatformAccountEntity>,
    )

    companion object {
        private const val PREVIEW_MAX_POINTS = 500

        fun factory(
            db: FitSyncDatabase,
            activityId: Long,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ActivityDetailViewModel(db, activityId) as T
        }
    }
}
