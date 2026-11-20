package com.fitsync.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fitsync.app.FitSyncApp
import com.fitsync.app.ui.components.LocalAppSnackbar
import com.fitsync.app.ui.screens.ActivitiesScreen
import com.fitsync.app.ui.screens.ActivityDetailScreen
import com.fitsync.app.ui.screens.BackgroundSettingsScreen
import com.fitsync.app.ui.screens.HomeScreen
import com.fitsync.app.ui.screens.PlatformsScreen
import com.fitsync.app.ui.screens.PrivacyAboutScreen
import com.fitsync.app.ui.screens.SettingsScreen
import com.fitsync.app.ui.screens.StorageSettingsScreen
import com.fitsync.app.ui.screens.SyncJobFilter
import com.fitsync.app.ui.screens.SyncJobsScreen
import com.fitsync.app.ui.screens.SyncRulesScreen
import com.fitsync.app.ui.screens.TrackMapScreen
import com.fitsync.app.ui.state.ActivitiesViewModel
import com.fitsync.app.ui.state.AppSharedUiViewModel
import com.fitsync.app.ui.state.HomeUiViewModel
import com.fitsync.app.ui.theme.FitShape
import com.fitsync.app.ui.theme.FitSpacing

private enum class MainTab(
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    HOME("概览", Icons.Filled.SpaceDashboard, Icons.Outlined.SpaceDashboard),
    ACTIVITIES(
        "活动",
        Icons.AutoMirrored.Filled.DirectionsBike,
        Icons.AutoMirrored.Outlined.DirectionsBike,
    ),
    SETTINGS("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
}

private const val MAIN_ROUTE = "main"

// 快速轻量转场：缩短两页并行停留时间，缓解重型页面（地图/图表）叠加压力。
private const val NavMs = 150
private const val NavExitMs = 90

@Composable
fun FitSyncAppRoot() {
    val app = FitSyncApp.instance
    val activityStoreOwner = checkNotNull(LocalViewModelStoreOwner.current)
    val sharedViewModel: AppSharedUiViewModel = viewModel(
        viewModelStoreOwner = activityStoreOwner,
        factory = remember(app) { AppSharedUiViewModel.factory(app) },
    )
    val homeViewModel: HomeUiViewModel = viewModel(
        viewModelStoreOwner = activityStoreOwner,
        factory = remember(app) { HomeUiViewModel.factory(app.database) },
    )
    val activitiesViewModel: ActivitiesViewModel = viewModel(
        viewModelStoreOwner = activityStoreOwner,
        factory = remember(app) { ActivitiesViewModel.factory(app.database) },
    )

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBottomBar = current == null || current == MAIN_ROUTE
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    var visitedTabs by remember { mutableStateOf(setOf(selectedTab)) }

    val homeListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val activitiesListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val settingsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    val appSnackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 统一在根部监听 ON_RESUME 刷新后台保护状态，避免各页面重复注册监听并在主线程做 IPC。
    DisposableEffect(lifecycleOwner, sharedViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                sharedViewModel.refreshBackgroundStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun selectTab(tab: MainTab) {
        if (selectedTab == tab) return
        focusManager.clearFocus()
        visitedTabs = visitedTabs + tab
        selectedTab = tab
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(appSnackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                Box(
                    modifier = Modifier.background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = FitSpacing.md, vertical = FitSpacing.sm),
                ) {
                    NavigationBar(
                        modifier = Modifier.height(70.dp).clip(FitShape.hero),
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        MainTab.entries.forEach { tab ->
                            val selected = selectedTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { selectTab(tab) },
                                icon = {
                                    Icon(
                                        if (selected) tab.selectedIcon else tab.icon,
                                        contentDescription = tab.label,
                                    )
                                },
                                label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        CompositionLocalProvider(LocalAppSnackbar provides appSnackbarHostState) {
            NavHost(
                navController = navController,
                startDestination = MAIN_ROUTE,
                modifier = Modifier.padding(padding),
                enterTransition = {
                    fadeIn(tween(NavMs, easing = FastOutSlowInEasing)) +
                        slideInHorizontally(tween(NavMs, easing = FastOutSlowInEasing)) { it / 16 }
                },
                exitTransition = { fadeOut(tween(NavExitMs)) },
                popEnterTransition = {
                    fadeIn(tween(NavMs, easing = FastOutSlowInEasing)) +
                        slideInHorizontally(tween(NavMs, easing = FastOutSlowInEasing)) { -it / 16 }
                },
                popExitTransition = { fadeOut(tween(NavExitMs)) },
            ) {
                composable(MAIN_ROUTE) {
                    BackHandler(enabled = selectedTab != MainTab.HOME) {
                        selectTab(MainTab.HOME)
                    }
                    KeepAliveTabContainer(
                        selectedTab = selectedTab,
                        visitedTabs = visitedTabs,
                        modifier = Modifier.fillMaxSize(),
                    ) { tab ->
                        when (tab) {
                            MainTab.HOME -> HomeScreen(
                                homeViewModel = homeViewModel,
                                sharedViewModel = sharedViewModel,
                                listState = homeListState,
                                onOpenActivities = { selectTab(MainTab.ACTIVITIES) },
                                onOpenJobs = { filter -> navController.navigate("jobs/${filter.routeValue}") },
                                onOpenPlatforms = { navController.navigate("settings/platforms") },
                            )
                            MainTab.ACTIVITIES -> ActivitiesScreen(
                                viewModel = activitiesViewModel,
                                listState = activitiesListState,
                                onOpenActivity = { id -> navController.navigate("activity/$id") },
                                onOpenPlatforms = { navController.navigate("settings/platforms") },
                            )
                            MainTab.SETTINGS -> SettingsScreen(
                                sharedViewModel = sharedViewModel,
                                listState = settingsListState,
                                onOpenPlatforms = { navController.navigate("settings/platforms") },
                                onOpenRules = { navController.navigate("settings/rules") },
                                onOpenBackground = { navController.navigate("settings/background") },
                                onOpenStorage = { navController.navigate("settings/storage") },
                                onOpenPrivacy = { navController.navigate("settings/privacy") },
                            )
                        }
                    }
                }
                composable("settings/platforms") {
                    PlatformsScreen(
                        sharedViewModel = sharedViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("settings/rules") {
                    SyncRulesScreen(
                        sharedViewModel = sharedViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenPlatforms = { navController.navigate("settings/platforms") },
                    )
                }
                composable("settings/background") {
                    BackgroundSettingsScreen(
                        sharedViewModel = sharedViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("settings/storage") {
                    StorageSettingsScreen(
                        sharedViewModel = sharedViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("settings/privacy") {
                    PrivacyAboutScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = "jobs/{filter}",
                    arguments = listOf(navArgument("filter") { type = NavType.StringType }),
                ) { entry ->
                    SyncJobsScreen(
                        filter = SyncJobFilter.fromRoute(entry.arguments?.getString("filter")),
                        onBack = { navController.popBackStack() },
                        onOpenActivity = { id -> navController.navigate("activity/$id") },
                    )
                }
                composable(
                    route = "activity/{activityId}",
                    arguments = listOf(navArgument("activityId") { type = NavType.LongType }),
                ) { entry ->
                    val detailActivityId = entry.arguments?.getLong("activityId") ?: return@composable
                    ActivityDetailScreen(
                        activityId = detailActivityId,
                        onBack = { navController.popBackStack() },
                        onOpenMap = { navController.navigate("activity/$detailActivityId/map") },
                    )
                }
                composable(
                    route = "activity/{activityId}/map",
                    arguments = listOf(navArgument("activityId") { type = NavType.LongType }),
                ) { entry ->
                    TrackMapScreen(
                        activityId = entry.arguments?.getLong("activityId") ?: return@composable,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

/**
 * 主 Tab 保活容器：
 * 首次访问时懒挂载并保持在组合树中，切换 Tab 时仅测量和放置当前选中的页面，
 * 隐藏的 Tab 不触发测量/绘制/触控，彻底消除切换主页面时的重新加载与状态丢失。
 */
@Composable
private fun KeepAliveTabContainer(
    selectedTab: MainTab,
    visitedTabs: Set<MainTab>,
    modifier: Modifier = Modifier,
    content: @Composable (MainTab) -> Unit,
) {
    val activeTabs = remember(visitedTabs, selectedTab) {
        MainTab.entries.filter { it in visitedTabs || it == selectedTab }
    }
    Layout(
        modifier = modifier,
        content = {
            activeTabs.forEach { tab ->
                key(tab) {
                    Box(Modifier.fillMaxSize()) {
                        content(tab)
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val activeIndex = activeTabs.indexOf(selectedTab)
        val placeable = if (activeIndex in measurables.indices) {
            measurables[activeIndex].measure(constraints)
        } else {
            null
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable?.placeRelative(0, 0)
        }
    }
}
