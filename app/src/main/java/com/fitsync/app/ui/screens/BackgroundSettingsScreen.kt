package com.fitsync.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitsync.app.ui.components.FitLoadingState
import com.fitsync.app.ui.components.FitTopBar
import com.fitsync.app.ui.state.AppSharedUiViewModel
import com.fitsync.app.ui.theme.FitSpacing

@Composable
fun BackgroundSettingsScreen(
    sharedViewModel: AppSharedUiViewModel,
    onBack: () -> Unit,
) {
    val sharedState by sharedViewModel.state.collectAsStateWithLifecycle()
    val status = sharedState.backgroundStatus

    Column(Modifier.fillMaxSize()) {
        FitTopBar(
            title = "后台运行",
            subtitle = "确保系统不会中断同步",
            onBack = onBack,
        )
        if (status == null) {
            FitLoadingState(message = "正在检查系统后台状态…")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = FitSpacing.screenGutter,
                    top = FitSpacing.sm,
                    end = FitSpacing.screenGutter,
                    bottom = FitSpacing.xxl,
                ),
            ) {
                item(key = "background-protection") {
                    BackgroundProtectionCard(
                        status = status,
                        onAllowBackground = { sharedViewModel.openBackgroundSettings(preferBatteryRequest = true) },
                        onOpenAppDetails = { sharedViewModel.openAppDetails() },
                        onOpenNotifications = { sharedViewModel.openNotificationSettings() },
                    )
                }
            }
        }
    }
}
