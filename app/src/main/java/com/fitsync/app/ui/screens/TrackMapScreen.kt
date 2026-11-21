package com.fitsync.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fitsync.app.FitSyncApp
import com.fitsync.app.ui.components.EmptyStateVariant
import com.fitsync.app.ui.components.FitEmptyState
import com.fitsync.app.ui.components.FitLoadingState
import com.fitsync.app.ui.components.FitMapLoadingOverlay
import com.fitsync.app.ui.components.FitTopBar
import com.fitsync.app.ui.components.TrackMap
import com.fitsync.app.ui.components.TrackPreview
import com.fitsync.app.ui.state.TrackMapViewModel
import com.fitsync.app.ui.theme.FitSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 全屏轨迹地图：详情页轨迹卡片的放大版，可缩放拖动、起终点标记。
 * 后台结合 TrackDecodeCache 解码轨迹，并在 150ms 导航转场完成后才挂载 MapView。
 */
@Composable
fun TrackMapScreen(activityId: Long, onBack: () -> Unit) {
    val app = FitSyncApp.instance
    val mapViewModel: TrackMapViewModel = viewModel(
        factory = remember(activityId) { TrackMapViewModel.factory(app.database, activityId) },
    )
    val uiState by mapViewModel.state.collectAsStateWithLifecycle()
    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()) }

    var mapMounted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(160)
        mapMounted = true
    }

    Column(Modifier.fillMaxSize()) {
        FitTopBar(
            title = "轨迹地图",
            subtitle = when {
                uiState.isLoading -> "正在加载轨迹…"
                uiState.activity != null -> uiState.activity?.let {
                    "${activitySummaryTitle(it)} · ${dateFormat.format(Date(it.startTimeEpochMs))}"
                }
                else -> null
            },
            onBack = onBack,
        )
        when {
            uiState.isLoading -> {
                FitLoadingState(message = "正在加载轨迹地图…")
            }

            uiState.points.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(FitSpacing.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    FitEmptyState(
                        title = "没有轨迹数据",
                        body = "这条活动没有 GPS 轨迹，无法在地图上展示。",
                        variant = EmptyStateVariant.NoResults,
                    )
                }
            }

            !mapMounted -> {
                val colors = MaterialTheme.colorScheme
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.surfaceContainerHigh),
                ) {
                    TrackPreview(
                        points = uiState.points,
                        modifier = Modifier.fillMaxSize().padding(FitSpacing.xxl),
                        color = colors.primary,
                        strokeWidth = FitSpacing.xxs,
                    )
                    FitMapLoadingOverlay(
                        message = "正在渲染地图…",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = FitSpacing.xxl),
                    )
                }
            }

            else -> {
                TrackMap(
                    points = uiState.points,
                    modifier = Modifier.fillMaxSize(),
                    interactive = true,
                )
            }
        }
    }
}
