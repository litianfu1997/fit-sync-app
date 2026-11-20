<script setup lang="ts">
import PageSkeleton from "./PageSkeleton.vue";
import { theme } from "../lib/theme";

withDefaults(
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
</script>
<template>
  <view v-if="refreshing" class="refresh-bar" aria-hidden="true">
    <view class="refresh-bar-fill" />
  </view>
  <view v-if="staleError" class="inline-error" role="status">
    <text>{{ staleError }}</text>
    <button class="text-button" @click="$emit('retry')">重试</button>
  </view>
  <PageSkeleton v-if="loading" :variant="skeleton || 'list'" />
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
