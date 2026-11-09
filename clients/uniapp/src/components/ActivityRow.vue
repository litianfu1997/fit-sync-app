<script setup lang="ts">
import type { Activity } from "../lib/types";
import { detail } from "../lib/api";
import { date, duration, km, platformName } from "../lib/format";
import { theme } from "../lib/theme";
defineProps<{ activity: Activity }>();
</script>
<template>
  <button class="activity-row" @click="detail(activity.id)">
    <view class="ride-icon"><image :src="`/static/activity${theme.dark ? '-dark' : ''}.svg`" /></view
    ><view class="activity-info"
      ><text class="activity-title">{{ activity.title || "骑行活动" }}</text
      ><view class="activity-meta"
        ><text>{{ date(activity.startTimeEpochMs) }}</text
        ><text class="meta-separator">·</text
        ><text>{{ platformName(activity.sourcePlatform) }}</text
        ><text v-if="activity.needsReview" class="tag amber">待核对</text></view
      ></view
    ><view class="activity-metrics"
      ><text class="distance"
        >{{ km(activity.distanceMeters) }}<text class="unit"> km</text></text
      ><text class="muted">{{ duration(activity.elapsedSeconds) }}</text></view
    ><text class="chevron">›</text>
  </button>
</template>
