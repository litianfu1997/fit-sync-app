package com.fitsync.app

import android.app.Application
import android.os.Trace
import androidx.work.Configuration
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.file.FitFileStore
import com.fitsync.app.data.repo.ActivityDeletionService
import com.fitsync.app.data.repo.ActivityMergeService
import com.fitsync.app.data.repo.ActivityMutationGate
import com.fitsync.app.data.repo.FitStorageJanitor
import com.fitsync.app.data.repo.ImportService
import com.fitsync.app.data.repo.PlatformAccountRepository
import com.fitsync.app.connectors.ConnectorRegistry
import com.fitsync.app.connectors.GarminAuthDiagnostics
import com.fitsync.app.fit.FitEngine
import com.fitsync.app.map.OsMapBootstrap
import com.fitsync.app.notification.SyncNotificationManager
import com.fitsync.app.security.SecureSessionStore
import com.fitsync.app.sync.DeliveryPlanner
import com.fitsync.app.sync.SyncCoordinator
import com.fitsync.app.sync.SyncScheduler
import com.fitsync.app.sync.MaintenanceScheduler
import com.fitsync.app.web.GarminUploadFilePreparer
import com.fitsync.app.web.GarminSilentSessionRefresher
import com.fitsync.app.web.WebSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class FitSyncApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    // Kotlin 的默认 lazy 使用同步锁，首次访问时只会构造一次，后台 Worker 与界面可安全共享。
    val database: FitSyncDatabase by lazy { FitSyncDatabase.getInstance(this) }
    val fitFileStore: FitFileStore by lazy { FitFileStore(this) }
    val fitEngine: FitEngine by lazy { FitEngine() }

    /** 地图（osmdroid）配置与瓦片缓存；首次访问或 App 启动时完成初始化。 */
    val osMapBootstrap: OsMapBootstrap by lazy { OsMapBootstrap(this) }
    val garminUploadFilePreparer: GarminUploadFilePreparer by lazy {
        GarminUploadFilePreparer(this, fitEngine)
    }
    private val activityMutationGate: ActivityMutationGate by lazy { ActivityMutationGate() }
    val webSessionManager: WebSessionManager by lazy {
        WebSessionManager(SecureSessionStore(this)).also { it.prepareCookies() }
    }
    val importService: ImportService by lazy {
        ImportService(
            db = database,
            fileStore = fitFileStore,
            fitEngine = fitEngine,
            mutationGate = activityMutationGate,
        )
    }
    val connectorRegistry: ConnectorRegistry by lazy {
        ConnectorRegistry(
            db = database,
            fileStore = fitFileStore,
            sessionManager = webSessionManager,
        )
    }
    val syncNotificationManager: SyncNotificationManager by lazy {
        SyncNotificationManager(this).also { it.createChannel() }
    }
    private val planner: DeliveryPlanner by lazy { DeliveryPlanner(database) }
    val activityDeletionService: ActivityDeletionService by lazy {
        ActivityDeletionService(
            db = database,
            fileStore = fitFileStore,
            notifications = syncNotificationManager,
            mutationGate = activityMutationGate,
        )
    }
    val activityMergeService: ActivityMergeService by lazy {
        ActivityMergeService(
            db = database,
            notifications = syncNotificationManager,
            planner = planner,
            mutationGate = activityMutationGate,
        )
    }
    val fitStorageJanitor: FitStorageJanitor by lazy {
        FitStorageJanitor(
            db = database,
            fileStore = fitFileStore,
            mutationGate = activityMutationGate,
        )
    }
    val syncCoordinator: SyncCoordinator by lazy {
        SyncCoordinator(
            db = database,
            importService = importService,
            connectors = connectorRegistry,
            planner = planner,
            notifications = syncNotificationManager,
            mutationGate = activityMutationGate,
            storageJanitor = fitStorageJanitor,
        )
    }
    val platformAccountRepository: PlatformAccountRepository by lazy {
        PlatformAccountRepository(
            db = database,
            connectors = connectorRegistry,
            sessions = webSessionManager,
            notifications = syncNotificationManager,
        )
    }
    val garminSilentSessionRefresher: GarminSilentSessionRefresher by lazy {
        GarminSilentSessionRefresher(
            db = database,
            sessions = webSessionManager,
            accounts = platformAccountRepository,
        )
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firstFrameHandled = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        Trace.beginSection("FitSync:app_onCreate")
        try {
            instance = this
            // 耗时 IO、osmdroid 配置、字体预热与旧凭据清理全部异步执行，不阻塞冷启动主线程。
            applicationScope.launch {
                osMapBootstrap.ensureInitialized()
                GarminAuthDiagnostics.init(this@FitSyncApp)
                WebSessionManager.clearLegacyStravaApiCredentialsOnce(this@FitSyncApp)
                runCatching {
                    resources.getFont(R.font.misans_regular)
                    resources.getFont(R.font.misans_medium)
                    resources.getFont(R.font.misans_bold)
                }
            }
        } finally {
            Trace.endSection()
        }
    }

    /** 首屏提交后再初始化后台调度，避免数据库、WorkManager 和维护任务进入启动关键路径。 */
    fun onFirstFrame() {
        if (!firstFrameHandled.compareAndSet(false, true)) return
        applicationScope.launch {
            // 自动同步开启时，冷启动延迟 2 秒再执行自动拉取，避免与首屏渲染/切页抢占 CPU 与数据库。
            val autoSyncEnabled = runCatching { SyncScheduler.ensureScheduled(this@FitSyncApp) }
                .getOrDefault(false)
            if (autoSyncEnabled) SyncScheduler.enqueueAutoNow(this@FitSyncApp, delayMs = 2_000L)
            MaintenanceScheduler.enqueue(this@FitSyncApp)
        }
    }

    companion object {
        lateinit var instance: FitSyncApp
            private set
    }
}
