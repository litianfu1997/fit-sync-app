<script setup lang="ts">
import { computed } from "vue";
import type { Activity } from "../lib/types";
import { detail } from "../lib/api";
import { date, duration, km, platformName, sportLabel } from "../lib/format";
import { theme } from "../lib/theme";

const props = defineProps<{ activity: Activity }>();

const platformsText = computed(() => {
  const list = props.activity.platforms?.length
    ? props.activity.platforms
    : [props.activity.sourcePlatform || "LOCAL"];
  return [...new Set(list)].map((code) => platformName(code)).join(" · ");
});

const trackPointsAttr = computed(() => {
  const pts = props.activity.track;
  if (!pts || pts.length < 2) return "";
  const valid = pts.filter(
    (p) => Number.isFinite(p.lat) && Number.isFinite(p.lon),
  );
  if (valid.length < 2) return "";
  let minLat = Infinity,
    maxLat = -Infinity,
    minLon = Infinity,
    maxLon = -Infinity;
  for (const point of valid) {
    if (point.lat < minLat) minLat = point.lat;
    if (point.lat > maxLat) maxLat = point.lat;
    if (point.lon < minLon) minLon = point.lon;
    if (point.lon > maxLon) maxLon = point.lon;
  }
  const cosLat = Math.max(
    0.2,
    Math.cos((((minLat + maxLat) / 2) * Math.PI) / 180),
  );
  const spanX = Math.max(1e-7, (maxLon - minLon) * cosLat);
  const spanY = Math.max(1e-7, maxLat - minLat);
  const size = 28;
  const pad = 6;
  const scale = Math.min(size / spanX, size / spanY);
  const offX = pad + (size - spanX * scale) / 2;
  const offY = pad + (size - spanY * scale) / 2;
  return valid
    .map((p) => {
      const x = (offX + (p.lon - minLon) * cosLat * scale).toFixed(1);
      const y = (offY + (maxLat - p.lat) * scale).toFixed(1);
      return `${x},${y}`;
    })
    .join(" ");
});
</script>
<template>
  <button class="activity-row" @click="detail(activity.id)">
    <view class="ride-icon"
      ><!-- #ifdef H5 --><svg
        v-if="trackPointsAttr"
        class="ride-thumb"
        viewBox="0 0 40 40"
        aria-hidden="true"
      >
        <polyline
          :points="trackPointsAttr"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
        /></svg
      ><image
        v-else
        :src="`/static/activity${theme.dark ? '-dark' : ''}.svg`"
      /><!-- #endif --><!-- #ifdef MP-WEIXIN --><image
        :src="`/static/activity${theme.dark ? '-dark' : ''}.svg`"
      /><!-- #endif --></view
    ><view class="activity-info"
      ><text class="activity-title">{{
        activity.title || sportLabel(activity.sportType)
      }}</text
      ><view class="activity-meta"
        ><text>{{ date(activity.startTimeEpochMs) }}</text
        ><text class="meta-separator">·</text
        ><text>{{ sportLabel(activity.sportType) }}</text
        ><text class="meta-separator">·</text
        ><text>{{ platformsText }}</text
        ><text v-if="activity.variantCount > 1" class="tag neutral"
          >{{ activity.variantCount }} 份副本</text
        ><text v-if="activity.needsReview" class="tag amber">待核对</text></view
      ></view
    ><view class="activity-metrics"
      ><text class="distance"
        >{{ km(activity.distanceMeters) }}<text class="unit"> km</text></text
      ><text class="muted">{{ duration(activity.elapsedSeconds) }}</text></view
    ><text class="chevron">›</text>
  </button>
</template>
