package com.fitsync.app.ui.state

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.dao.ActivityListItem
import com.fitsync.app.data.db.dao.ActivityListQuery
import com.fitsync.app.domain.model.DedupStatus
import com.fitsync.app.domain.model.SportType
import com.fitsync.app.fit.LatLng
import com.fitsync.app.fit.TrackDecodeCache
import com.fitsync.app.ui.screens.ActivityFilter
import com.fitsync.app.ui.screens.formatDuration
import com.fitsync.app.ui.screens.sportLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.plus

@Immutable
data class ActivityListUiModel(
    val id: Long,
    val title: String,
    val sportType: SportType,
    val dedupStatus: DedupStatus,
    val dateText: String,
    val distanceText: String,
    val durationText: String?,
    val sportText: String,
    val trackPoints: List<LatLng>,
)

/**
 * 活动列表 ViewModel（Activity 作用域）：
 * 1. PagingData 使用 `cachedIn(viewModelScope + Dispatchers.Default)` 保活，切 Tab 与二级页返回零重查；
 * 2. 轨迹摘要 JSON 解析与日期格式化全部在后台线程池结合 [TrackDecodeCache] 完成，主线程零 JSON 解析。
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ActivitiesViewModel(
    private val db: FitSyncDatabase,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(ActivityFilter.ALL)
    val filter: StateFlow<ActivityFilter> = _filter.asStateFlow()

    private val _oldestFirst = MutableStateFlow(false)
    val oldestFirst: StateFlow<Boolean> = _oldestFirst.asStateFlow()

    private val debouncedQuery = _query
        .debounce { if (it.isBlank()) 0L else 250L }
        .map { it.trim() }
        .distinctUntilChanged()

    private val listQueryFlow = combine(
        debouncedQuery,
        _filter,
        _oldestFirst,
    ) { search, activeFilter, oldest ->
        ActivityListQuery(
            searchText = search,
            filter = activeFilter.name,
            oldestFirst = oldest,
        )
    }.distinctUntilChanged()

    // 在后台调度器中缓存与变换 PageEvent，保证 40 条 JSON 解码一次性在 Default 线程完成。
    private val pagingCacheScope = viewModelScope + Dispatchers.Default

    val activities: Flow<PagingData<ActivityListUiModel>> = listQueryFlow
        .flatMapLatest { listQuery ->
            Pager(
                config = PagingConfig(
                    pageSize = 40,
                    initialLoadSize = 40,
                    prefetchDistance = 15,
                    enablePlaceholders = false,
                    maxSize = 200,
                ),
                pagingSourceFactory = {
                    db.canonicalActivityDao().pagingSource(
                        query = listQuery.searchText,
                        filter = listQuery.filter,
                        oldestFirst = listQuery.oldestFirst,
                    )
                },
            ).flow.map { pagingData ->
                pagingData.map { item -> item.toUiModel() }
            }.flowOn(Dispatchers.Default)
        }
        .cachedIn(pagingCacheScope)

    fun updateQuery(value: String) {
        _query.value = value
    }

    fun updateFilter(next: ActivityFilter) {
        _filter.value = next
    }

    fun toggleSort() {
        _oldestFirst.value = !_oldestFirst.value
    }

    fun clearFilters() {
        _query.value = ""
        _filter.value = ActivityFilter.ALL
    }

    companion object {
        private val listDateFormatter: DateTimeFormatter = DateTimeFormatter
            .ofPattern("MM月dd日 · HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())

        private fun ActivityListItem.toUiModel(): ActivityListUiModel = ActivityListUiModel(
            id = id,
            title = title?.takeIf { it.isNotBlank() } ?: sportLabel(sportType),
            sportType = sportType,
            dedupStatus = dedupStatus,
            dateText = listDateFormatter.format(Instant.ofEpochMilli(startTimeEpochMs)),
            distanceText = distanceMeters?.let { "%.1f km".format(it / 1000) } ?: "--",
            durationText = elapsedSeconds?.let(::formatDuration),
            sportText = sportLabel(sportType),
            trackPoints = TrackDecodeCache.decodeSummaryTrack(trackJson),
        )

        fun factory(db: FitSyncDatabase): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ActivitiesViewModel(db) as T
        }
    }
}
