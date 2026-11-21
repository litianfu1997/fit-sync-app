package com.fitsync.app.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.dao.SyncJobListItem
import com.fitsync.app.ui.screens.SyncJobFilter
import kotlinx.coroutines.flow.Flow

/**
 * 同步任务页 ViewModel（绑定任务页 NavBackStackEntry）：
 * 通过 [cachedIn] 缓存分页数据，点进活动详情再返回任务页时零重查。
 */
class SyncJobsViewModel(
    db: FitSyncDatabase,
    filter: SyncJobFilter,
) : ViewModel() {

    val jobs: Flow<PagingData<SyncJobListItem>> = Pager(
        config = PagingConfig(
            pageSize = 30,
            initialLoadSize = 30,
            prefetchDistance = 10,
            enablePlaceholders = false,
            maxSize = 150,
        ),
        pagingSourceFactory = { db.deliveryJobDao().pagingSource(filter.routeValue) },
    ).flow.cachedIn(viewModelScope)

    companion object {
        fun factory(
            db: FitSyncDatabase,
            filter: SyncJobFilter,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SyncJobsViewModel(db, filter) as T
        }
    }
}
