<script setup lang="ts">
import { theme } from "../lib/theme";
defineProps<{
  loading: boolean;
  error?: string;
  empty?: boolean;
  emptyTitle?: string;
  emptyText?: string;
}>();
defineEmits<{ retry: [] }>();
</script>
<template>
  <view v-if="loading" class="card empty-state compact"
    ><view class="spinner" /><text class="muted">正在读取最新数据…</text></view
  >
  <view v-else-if="error" class="card empty-state compact"
    ><text class="empty-title">数据暂时未能加载</text
    ><text class="error-text">{{ error }}</text
    ><button class="btn secondary" @click="$emit('retry')">重试</button></view
  >
  <view v-else-if="empty" class="card empty-state"
    ><image class="empty-icon" :src="`/static/activity${theme.dark ? '-dark' : ''}.svg`" /><text
      class="empty-title"
      >{{ emptyTitle || "这里还没有记录" }}</text
    ><text class="muted">{{
      emptyText || "完成第一次同步后，数据会出现在这里。"
    }}</text
    ><slot name="empty-action"
  /></view>
  <slot v-else />
</template>
