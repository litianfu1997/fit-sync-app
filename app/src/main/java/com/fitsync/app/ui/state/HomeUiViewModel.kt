package com.fitsync.app.ui.state

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.DeliveryJobEntity
import com.fitsync.app.data.db.entity.ImportJobEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Immutable
data class HomeStats(
    val discovered: Int = 0,
    val synced: Int = 0,
    val pending: Int = 0,
    val review: Int = 0,
    val failed: Int = 0,
)

@Immutable
data class HomeDashboardState(
    val isLoaded: Boolean = false,
    val stats: HomeStats = HomeStats(),
    val recentImports: List<ImportJobEntity> = emptyList(),
    val waitingJobs: List<DeliveryJobEntity> = emptyList(),
)

/**
 * 概览页仪表盘 ViewModel（Activity 作用域保活，切页或返回不重查统计快照）。
 */
class HomeUiViewModel(db: FitSyncDatabase) : ViewModel() {

    val state: StateFlow<HomeDashboardState> = combine(
        db.canonicalActivityDao().observeHomeSnapshot(),
        db.importJobDao().observeRecent(5),
        db.deliveryJobDao().observeWaitingForUser(4),
    ) { snapshot, imports, waiting ->
        HomeDashboardState(
            isLoaded = true,
            stats = HomeStats(
                discovered = snapshot.discovered,
                synced = snapshot.synced,
                pending = snapshot.pending,
                review = snapshot.review,
                failed = snapshot.failed,
            ),
            recentImports = imports,
            waitingJobs = waiting,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(60_000),
        initialValue = HomeDashboardState(),
    )

    companion object {
        fun factory(db: FitSyncDatabase): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeUiViewModel(db) as T
        }
    }
}
