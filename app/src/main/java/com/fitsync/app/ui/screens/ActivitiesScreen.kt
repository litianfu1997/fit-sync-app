package com.fitsync.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.fitsync.app.domain.model.DedupStatus
import com.fitsync.app.domain.model.SportType
import com.fitsync.app.performance.PerformanceTrace
import com.fitsync.app.ui.components.EmptyStateVariant
import com.fitsync.app.ui.components.FitEmptyState
import com.fitsync.app.ui.components.FitListRow
import com.fitsync.app.ui.components.FitPagingFooter
import com.fitsync.app.ui.components.FitRefreshBanner
import com.fitsync.app.ui.components.FitSkeletonListRow
import com.fitsync.app.ui.components.FitTopBar
import com.fitsync.app.ui.components.IconBadge
import com.fitsync.app.ui.components.IconBadgeSize
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
import com.fitsync.app.ui.components.TrackThumbnail
import com.fitsync.app.ui.state.ActivitiesViewModel
import com.fitsync.app.ui.state.ActivityListUiModel
import com.fitsync.app.ui.theme.FitShape
import com.fitsync.app.ui.theme.FitSpacing
import com.fitsync.app.ui.theme.FitType

enum class ActivityFilter(val label: String) {
    ALL("全部"), CYCLING("骑行"), RUNNING("跑步"), REVIEW("待复核")
}

@Composable
fun ActivitiesScreen(
    viewModel: ActivitiesViewModel,
    listState: LazyListState = rememberLazyListState(),
    onOpenActivity: (Long) -> Unit = {},
    onOpenPlatforms: () -> Unit = {},
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val oldestFirst by viewModel.oldestFirst.collectAsStateWithLifecycle()
    val activities = viewModel.activities.collectAsLazyPagingItems()

    LaunchedEffect(Unit) {
        PerformanceTrace.section("FitSync:activity_list_first_frame") {
            withFrameNanos { }
        }
    }

    val showingEverything = query.isBlank() && filter == ActivityFilter.ALL
    val refreshState = activities.loadState.refresh
    val isInitialLoading = refreshState is LoadState.Loading && activities.itemCount == 0
    val isRefreshingWithContent = refreshState is LoadState.Loading && activities.itemCount > 0
    val isRefreshError = refreshState is LoadState.Error && activities.itemCount == 0

    Column(Modifier.fillMaxSize()) {
        FitTopBar(title = "运动记录", subtitle = "所有来源已合并去重")
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = FitSpacing.screenGutter,
                top = FitSpacing.sm,
                end = FitSpacing.screenGutter,
                bottom = FitSpacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            item(key = "search-field") {
                ActivitySearchField(
                    query = query,
                    onQueryChange = viewModel::updateQuery,
                )
            }
            item(key = "sort-row") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (isInitialLoading) "正在加载活动…" else "${activities.itemCount} 条已加载",
                        style = FitType.dataLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilterChip(
                        modifier = Modifier.height(36.dp),
                        selected = true,
                        onClick = viewModel::toggleSort,
                        label = { Text(if (oldestFirst) "时间正序" else "时间倒序") },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "切换时间排序",
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
            item(key = "filter-chips") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
                ) {
                    ActivityFilter.entries.forEach { option ->
                        FilterChip(
                            modifier = Modifier.height(36.dp),
                            selected = filter == option,
                            onClick = { viewModel.updateFilter(option) },
                            label = { Text(option.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
            }
            when {
                isInitialLoading -> {
                    item(key = "first-load-banner") {
                        FitRefreshBanner(message = "正在加载活动列表…")
                    }
                    items(5, key = { "skeleton-$it" }) {
                        FitSkeletonListRow()
                    }
                }

                isRefreshError -> {
                    item(key = "refresh-error") {
                        val error = (refreshState as LoadState.Error).error
                        FitEmptyState(
                            title = "活动列表加载失败",
                            body = error.localizedMessage ?: "读取本地运动记录时发生异常。",
                            variant = EmptyStateVariant.Error,
                            actionLabel = "重试",
                            onAction = { activities.retry() },
                        )
                    }
                }

                refreshState is LoadState.NotLoading && activities.itemCount == 0 -> {
                    item(key = "empty-state") {
                        if (showingEverything) {
                            FitEmptyState(
                                title = "还没有活动",
                                body = "从平台拉取，或把 FIT 文件分享到 FitSync。",
                                variant = EmptyStateVariant.FirstRun,
                                steps = listOf("连接平台账号", "分享或拉取 FIT", "自动补齐到其他平台"),
                                actionLabel = "连接平台",
                                onAction = onOpenPlatforms,
                            )
                        } else {
                            FitEmptyState(
                                title = "没有匹配结果",
                                body = "试试其他关键词或筛选条件。",
                                variant = EmptyStateVariant.NoResults,
                                icon = Icons.Default.Search,
                                actionLabel = "清除筛选",
                                onAction = viewModel::clearFilters,
                            )
                        }
                    }
                }

                else -> {
                    if (isRefreshingWithContent) {
                        item(key = "refresh-banner") {
                            FitRefreshBanner(message = "正在筛选活动…")
                        }
                    }
                    items(
                        count = activities.itemCount,
                        key = activities.itemKey { it.id },
                        contentType = { "activity" },
                    ) { index ->
                        activities[index]?.let { activity ->
                            ActivityRow(activity, onOpenActivity)
                        }
                    }
                    item(key = "paging-footer") {
                        FitPagingFooter(
                            append = activities.loadState.append,
                            itemCount = activities.itemCount,
                            onRetry = { activities.retry() },
                        )
                    }
                }
            }
        }
    }
}

/** 填充式搜索框：无描边噪音，高度 48（保证清除按钮满足最小触控区）。 */
@Composable
private fun ActivitySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = FitShape.control,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.height(FitSpacing.touchTarget).padding(horizontal = FitSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(FitSpacing.iconSm),
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).padding(start = FitSpacing.sm),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                "搜索标题或运动类型",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotBlank()) {
                // 不覆写 size：IconButton 默认保证 48dp 最小触控区。
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "清除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(
    activity: ActivityListUiModel,
    onOpenActivity: (Long) -> Unit,
) {
    FitListRow(
        title = activity.title,
        subtitle = "${activity.dateText} · ${activity.sportText}",
        leading = {
            // 有 GPS 就画路线形状：一列骑行动态里，形状比运动图标更好认。
            // 无 GPS（骑行台等）退回运动图标，两种行共用同一套尺寸语汇。
            if (activity.trackPoints.size >= 2) {
                TrackThumbnail(activity.trackPoints)
            } else {
                IconBadge(sportIcon(activity.sportType), size = IconBadgeSize.Md)
            }
        },
        trailing = if (activity.dedupStatus == DedupStatus.NEEDS_REVIEW) {
            { StatusPill("待复核", StatusTone.WARNING) }
        } else null,
        meta = {
            Text(
                activity.distanceText,
                style = FitType.numericMd,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            activity.durationText?.let {
                Text(
                    "·",
                    style = MaterialTheme.typography.bodySmall,
                    // 用 outline 会淡到 1.1–1.6:1，两个数据点会糊成一片；
                    // 分隔符取 onSurfaceVariant 的一半，看得见又不抢数字。
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
                Text(
                    it,
                    style = FitType.numericSm,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        onClick = { onOpenActivity(activity.id) },
        showChevron = true,
    )
}

fun sportLabel(sport: SportType): String = when (sport) {
    SportType.CYCLING -> "骑行"
    SportType.INDOOR_CYCLING -> "室内骑行"
    SportType.MTB -> "山地骑行"
    SportType.GRAVEL -> "砾石骑行"
    SportType.RUNNING -> "跑步"
    SportType.OTHER -> "其他运动"
    SportType.UNKNOWN -> "未知运动"
}

fun sportIcon(sport: SportType): ImageVector = when (sport) {
    SportType.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
    SportType.INDOOR_CYCLING -> Icons.AutoMirrored.Filled.DirectionsBike
    SportType.CYCLING, SportType.MTB, SportType.GRAVEL -> Icons.AutoMirrored.Filled.DirectionsBike
    SportType.OTHER, SportType.UNKNOWN -> Icons.Default.Route
}

fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "%d:%02d".format(h, m) else "%d 分".format(m)
}
