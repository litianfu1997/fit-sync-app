<script setup lang="ts">
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import SessionGate from "../../components/SessionGate.vue";
import LoadState from "../../components/LoadState.vue";
import TrackMap from "../../components/TrackMap.vue";
import { detail, go, session } from "../../lib/api";
import { fetchActivity } from "../../lib/data-cache";
import { useLoad } from "../../lib/page";
import { date, duration, km } from "../../lib/format";
import { theme, themeStyle } from "../../lib/theme";
import type { Activity } from "../../lib/types";

const id = ref("");
const activity = ref<Activity | null>(null);
const miniHeaderStyle: Record<string, string> = {};
// #ifdef MP-WEIXIN
const menu = uni.getMenuButtonBoundingClientRect();
const windowInfo = uni.getSystemInfoSync();
if (menu.height > 0) {
  miniHeaderStyle.paddingTop = `${menu.top}px`;
  miniHeaderStyle.paddingBottom = "8px";
  miniHeaderStyle.minHeight = `${menu.bottom + 8}px`;
  miniHeaderStyle.paddingRight = `${windowInfo.windowWidth - menu.left + 12}px`;
}
// #endif

const subtitle = computed(() => {
  if (!activity.value) return "";
  return `${activity.value.title} · ${date(activity.value.startTimeEpochMs)}`;
});
const hasTrack = computed(() => (activity.value?.track?.length || 0) > 0);
const summaryPill = computed(() => {
  if (!activity.value) return "";
  return `${km(activity.value.distanceMeters)} 公里 · ${duration(activity.value.elapsedSeconds)}`;
});

const { loading, refreshing, fatalError, staleError, reload } = useLoad(async (force) => {
  if (!id.value) {
    activity.value = null;
    return;
  }
  activity.value = await fetchActivity(id.value, force);
}, { ttl: 300_000, scope: "activity" });

const tiles = ref<"loading" | "ready" | "failed">("loading");
const mapRef = ref<{ retryTiles: () => void } | null>(null);

function onTilesState(state: "loading" | "ready" | "failed") {
  tiles.value = state;
}
function retryTiles() {
  tiles.value = "loading";
  mapRef.value?.retryTiles();
}

onLoad((query) => {
  id.value = query?.id ? decodeURIComponent(String(query.id)) : "";
});

function goBack() {
  if (getCurrentPages().length > 1) {
    uni.navigateBack();
    return;
  }
  if (id.value) {
    detail(id.value);
    return;
  }
  go("activities");
}
</script>

<template>
  <view
    class="fullscreen-map-shell"
    :class="{ 'theme-dark': theme.dark }"
    :style="themeStyle"
  >
    <view class="fullscreen-map-topbar" :style="miniHeaderStyle">
      <button class="btn secondary fullscreen-map-back" @click="goBack">返回</button>
      <view class="fullscreen-map-title-group">
        <text class="fullscreen-map-title">轨迹地图</text>
        <text v-if="subtitle" class="fullscreen-map-subtitle">{{ subtitle }}</text>
      </view>
      <!-- #ifdef H5 -->
      <view v-if="summaryPill" class="fullscreen-map-pill">
        <text>{{ summaryPill }}</text>
      </view>
      <!-- #endif -->
    </view>

    <view v-if="!session.token" class="fullscreen-map-state">
      <SessionGate @ready="reload(true)" />
    </view>

    <view
      v-else-if="loading || fatalError || !activity"
      class="fullscreen-map-state"
    >
      <LoadState
        :loading="loading"
        :refreshing="refreshing"
        :error="fatalError"
        :stale-error="staleError"
        :empty="!activity"
        skeleton="map"
        empty-title="没有找到这条活动"
        empty-text="它可能已被删除，或暂时无法读取。"
        @retry="reload(true)"
      >
        <template #empty-action>
          <button class="btn secondary" @click="goBack">返回活动</button>
        </template>
      </LoadState>
    </view>

    <view v-else class="fullscreen-map-body">
      <TrackMap
        v-if="hasTrack"
        ref="mapRef"
        :points="activity.track"
        :interactive="true"
        height="100%"
        @tiles-state="onTilesState"
      />
      <!-- #ifdef H5 -->
      <view v-if="hasTrack && tiles !== 'ready'" class="map-tile-overlay">
        <view v-if="tiles === 'failed'" class="card map-tile-card">
          <text class="empty-title">地图底图加载失败</text>
          <text class="muted">轨迹仍然可以查看，网络恢复后可重试加载底图。</text>
          <button class="btn secondary small" @click="retryTiles">
            重试底图
          </button>
        </view>
        <view v-else class="map-tile-loading" role="status">
          <view class="spinner" /><text>地图加载中…</text>
        </view>
      </view>
      <!-- #endif -->
      <view v-else class="fullscreen-map-state">
        <view class="card empty-state">
          <image
            class="empty-icon"
            :src="`/static/route${theme.dark ? '-dark' : ''}.svg`"
          />
          <text class="empty-title">没有轨迹数据</text>
          <text class="muted">这条活动未包含可用于地图绘制的 GPS 经纬度坐标。</text>
          <button class="btn secondary" @click="goBack">返回详情</button>
        </view>
      </view>
    </view>
  </view>
</template>
