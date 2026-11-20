package com.fitsync.app.ui.state

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.fitsync.app.FitSyncApp
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.AppSettingsEntity
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.data.db.entity.SyncRuleEntity
import com.fitsync.app.sync.BackgroundRunGuard
import com.fitsync.app.sync.BackgroundRunStatus
import com.fitsync.app.sync.SyncScheduler
import com.fitsync.app.sync.syncErrorMessage
import com.fitsync.app.sync.toSyncWorkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
sealed class ManualSyncUiState(val isActive: Boolean) {
    data object Idle : ManualSyncUiState(false)
    data class Queued(val message: String) : ManualSyncUiState(true)
    data object Running : ManualSyncUiState(true)
    data class Finished(val message: String) : ManualSyncUiState(false)
    data class Failed(val message: String) : ManualSyncUiState(false)
}

@Immutable
data class SharedAppUiState(
    val isLoaded: Boolean = false,
    val accounts: List<PlatformAccountEntity> = emptyList(),
    val rules: List<SyncRuleEntity> = emptyList(),
    val settings: AppSettingsEntity? = null,
    val backgroundStatus: BackgroundRunStatus? = null,
    val bannerDismissedRecently: Boolean = false,
    val batteryPromptDone: Boolean = false,
    val manualSyncState: ManualSyncUiState = ManualSyncUiState.Idle,
)

private data class BackgroundGuardSnapshot(
    val status: BackgroundRunStatus? = null,
    val bannerDismissedRecently: Boolean = false,
    val batteryPromptDone: Boolean = false,
)

/**
 * Activity 作用域共享状态：在主导航三个 Tab 与全部设置子页之间共享账号、规则、设置、
 * 后台保护检测和手动同步进度，彻底消除切页重新订阅与主线程 Binder IPC 卡顿。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppSharedUiViewModel(
    private val appContext: Context,
    db: FitSyncDatabase,
) : ViewModel() {

    private val guard = BackgroundRunGuard(appContext)
    private val refreshTick = MutableStateFlow(0)
    private val manualSyncStateFlow = MutableStateFlow<ManualSyncUiState>(ManualSyncUiState.Idle)

    private val dbStream = combine(
        db.platformAccountDao().observeAll(),
        db.syncRuleDao().observeAll(),
        db.appSettingsDao().observe(),
    ) { accounts, rules, settings ->
        Triple(accounts, rules, settings)
    }

    private val backgroundGuardStream = combine(
        db.appSettingsDao().observe().map { it?.autoSyncEnabled != false }.distinctUntilChanged(),
        refreshTick,
    ) { autoSyncEnabled, _ ->
        autoSyncEnabled
    }.mapLatest { autoSyncEnabled ->
        withContext(Dispatchers.IO) {
            BackgroundGuardSnapshot(
                status = guard.evaluate(autoSyncEnabled),
                bannerDismissedRecently = guard.isBannerDismissedRecently(),
                batteryPromptDone = guard.isBatteryPromptDone(),
            )
        }
    }.onStart { emit(BackgroundGuardSnapshot()) }

    val state: StateFlow<SharedAppUiState> = combine(
        dbStream,
        backgroundGuardStream,
        manualSyncStateFlow,
    ) { (accounts, rules, settings), guardSnapshot, manualSync ->
        SharedAppUiState(
            isLoaded = true,
            accounts = accounts,
            rules = rules,
            settings = settings,
            backgroundStatus = guardSnapshot.status,
            bannerDismissedRecently = guardSnapshot.bannerDismissedRecently,
            batteryPromptDone = guardSnapshot.batteryPromptDone,
            manualSyncState = manualSync,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SharedAppUiState(),
    )

    init {
        viewModelScope.launch {
            // 让出首屏启动关键路径，WorkManager 实例化放到 IO 线程。
            delay(60)
            val manager = withContext(Dispatchers.IO) { WorkManager.getInstance(appContext) }
            manager.getWorkInfosForUniqueWorkLiveData(SyncScheduler.MANUAL_NAME)
                .asFlow()
                .map { works -> manualSyncUiState(works.lastOrNull()) }
                .distinctUntilChanged()
                .collect { manualSyncStateFlow.value = it }
        }
    }

    fun refreshBackgroundStatus() {
        refreshTick.value += 1
    }

    fun dismissBackgroundBanner() {
        viewModelScope.launch(Dispatchers.IO) {
            guard.dismissBanner()
            refreshTick.value += 1
        }
    }

    fun markBatteryPromptDone() {
        viewModelScope.launch(Dispatchers.IO) {
            guard.markBatteryPromptDone()
            refreshTick.value += 1
        }
    }

    fun openBackgroundSettings(preferBatteryRequest: Boolean = true) {
        guard.openBackgroundSettings(preferBatteryRequest = preferBatteryRequest)
        refreshBackgroundStatus()
    }

    fun openNotificationSettings() {
        guard.openNotificationSettings()
        refreshBackgroundStatus()
    }

    fun openAppDetails() {
        guard.openAppDetails()
        refreshBackgroundStatus()
    }

    companion object {
        fun factory(app: FitSyncApp): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppSharedUiViewModel(app.applicationContext, app.database) as T
        }
    }
}

private fun manualSyncUiState(workInfo: WorkInfo?): ManualSyncUiState = when (workInfo?.state) {
    null -> ManualSyncUiState.Idle
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ManualSyncUiState.Queued(
        if (workInfo.runAttemptCount > 0) "同步暂时未完成，等待系统重试" else "已进入后台同步队列，等待网络条件",
    )
    WorkInfo.State.RUNNING -> ManualSyncUiState.Running
    WorkInfo.State.SUCCEEDED -> ManualSyncUiState.Finished(
        workInfo.outputData.toSyncWorkResult()?.userMessage() ?: "上次同步已完成",
    )
    WorkInfo.State.FAILED -> ManualSyncUiState.Failed(
        workInfo.outputData.syncErrorMessage()?.let { "同步失败：$it" } ?: "同步失败，请稍后重试",
    )
    WorkInfo.State.CANCELLED -> ManualSyncUiState.Failed("同步已取消，可重新发起")
}
