<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import PageSkeleton from "./PageSkeleton.vue";
import { theme } from "../lib/theme";

const props = withDefaults(
  defineProps<{
    loading: boolean;
    refreshing?: boolean;
    error?: string;
    staleError?: string;
    empty?: boolean;
    emptyTitle?: string;
    emptyText?: string;
    skeleton?: "hero" | "list" | "detail" | "map";
  }>(),
  { refreshing: false, error: "", staleError: "", empty: false },
);
defineEmits<{ retry: [] }>();

/** 缓存直出的快加载通常几十毫秒内完成，骨架屏一闪而过反而刺眼；
 * 仅当加载持续超过该时长才真正显示骨架，之前用顶部细进度条占位。 */
const SKELETON_DELAY = 150;
const skeletonVisible = ref(false);
let skeletonTimer: ReturnType<typeof setTimeout> | null = null;
watch(
  () => props.loading,
  (loading) => {
    if (skeletonTimer) {
      clearTimeout(skeletonTimer);
      skeletonTimer = null;
    }
    if (loading) {
      skeletonTimer = setTimeout(() => {
        skeletonVisible.value = true;
      }, SKELETON_DELAY);
    } else {
      skeletonVisible.value = false;
    }
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  if (skeletonTimer) clearTimeout(skeletonTimer);
});
</script>
<template>
  <view v-if="refreshing" class="refresh-bar" aria-hidden="true">
    <view class="refresh-bar-fill" />
  </view>
  <view v-if="staleError" class="inline-error" role="status">
    <text>{{ staleError }}</text>
    <button class="text-button" @click="$emit('retry')">重试</button>
  </view>
  <template v-if="loading">
    <view v-if="!skeletonVisible" class="refresh-bar" aria-hidden="true">
      <view class="refresh-bar-fill" />
    </view>
    <PageSkeleton v-else :variant="skeleton || 'list'" />
  </template>
  <view v-else-if="error" class="card empty-state compact"
    ><text class="empty-title">数据暂时未能加载</text
    ><text class="error-text">{{ error }}</text
    ><button class="btn secondary" @click="$emit('retry')">重试</button></view
  >
  <view v-else-if="empty" class="card empty-state"
    ><image
      class="empty-icon"
      :src="`/static/activity${theme.dark ? '-dark' : ''}.svg`"
    /><text class="empty-title">{{
      emptyTitle || "这里还没有记录"
    }}</text
    ><text class="muted">{{
      emptyText || "完成第一次同步后，数据会出现在这里。"
    }}</text
    ><slot name="empty-action"
  /></view>
  <slot v-else />
</template>
