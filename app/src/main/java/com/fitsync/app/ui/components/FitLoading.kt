package com.fitsync.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import com.fitsync.app.ui.theme.FitShape
import com.fitsync.app.ui.theme.FitSpacing

/** 呼吸骨架块：alpha 0.35 → 0.6，用 graphicsLayer 避免每帧触发全节点重绘。 */
@Composable
fun FitSkeleton(
    modifier: Modifier = Modifier,
    shape: Shape = FitShape.control,
) {
    val transition = rememberInfiniteTransition(label = "fit-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "fit-skeleton-alpha",
    )
    Box(
        modifier
            .clip(shape)
            .graphicsLayer { this.alpha = alpha }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** 列表行骨架：与 FitListRow 相同的骨架节奏，用于首屏加载。 */
@Composable
fun FitSkeletonListRow(modifier: Modifier = Modifier) {
    FitCard(
        modifier = modifier,
        contentPadding = PaddingValues(FitSpacing.rowPaddingH, FitSpacing.rowPaddingV),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            FitSkeleton(Modifier.size(36.dp), FitShape.control)
            Column(Modifier.weight(1f)) {
                FitSkeleton(Modifier.fillMaxWidth(0.46f).height(14.dp))
                FitSkeleton(
                    Modifier.padding(top = FitSpacing.sm).fillMaxWidth(0.72f).height(12.dp),
                )
            }
        }
    }
}

/**
 * 分页尾部：加载中显示骨架行，失败显示重试，末尾显示已加载全部。
 */
@Composable
fun FitPagingFooter(
    append: LoadState,
    itemCount: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        append is LoadState.Loading -> FitSkeletonListRow(modifier)
        append is LoadState.Error -> {
            Row(
                modifier = modifier.fillMaxWidth().padding(vertical = FitSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    "加载失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
        itemCount > 0 -> {
            Box(
                modifier = modifier.fillMaxWidth().padding(vertical = FitSpacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "已加载全部 $itemCount 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 小尺寸加载指示：用于按需出现的局部等待。 */
@Composable
fun FitInlineLoading(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
    ) {
        FitSkeleton(Modifier.width(18.dp).height(18.dp), FitShape.indicator)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 整页居中的加载提示：替代空白等待或闪烁空状态。 */
@Composable
fun FitLoadingState(
    message: String = "正在加载…",
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 列表顶部紧凑刷新/筛选条：保留已有内容，不清空列表闪白屏。 */
@Composable
fun FitRefreshBanner(
    message: String = "正在更新列表…",
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(FitShape.control)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = FitSpacing.md, vertical = FitSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 活动详情页首帧骨架：防止正文异步到达前底部“分发/删除”模块先顶到屏幕上方跳变。 */
@Composable
fun FitActivityDetailSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = FitSpacing.screenGutter,
                top = FitSpacing.sm,
                end = FitSpacing.screenGutter,
                bottom = FitSpacing.xxl,
            ),
        verticalArrangement = Arrangement.spacedBy(FitSpacing.md),
    ) {
        FitRefreshBanner(message = "正在加载活动详情…")
        FitSkeleton(
            modifier = Modifier.fillMaxWidth().height(168.dp),
            shape = FitShape.hero,
        )
        FitCard(contentPadding = PaddingValues(FitSpacing.md)) {
            Column(verticalArrangement = Arrangement.spacedBy(FitSpacing.md)) {
                repeat(2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
                    ) {
                        repeat(3) {
                            Column(Modifier.weight(1f)) {
                                FitSkeleton(Modifier.fillMaxWidth(0.7f).height(18.dp))
                                FitSkeleton(
                                    Modifier.padding(top = FitSpacing.xs).fillMaxWidth(0.45f).height(11.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        FitCard(contentPadding = PaddingValues(FitSpacing.md)) {
            FitSkeleton(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                shape = FitShape.control,
            )
            FitSkeleton(Modifier.padding(top = FitSpacing.md).fillMaxWidth().height(44.dp))
        }
        FitSkeletonListRow()
        FitSkeletonListRow()
    }
}

/** 地图与重型视图挂载期间的悬浮 Loading 胶囊提示。 */
@Composable
fun FitMapLoadingOverlay(
    message: String = "正在加载轨迹地图…",
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = FitShape.pill,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = FitSpacing.md, vertical = FitSpacing.xs + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
