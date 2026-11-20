<script setup lang="ts">
import Skeleton from "./Skeleton.vue";

withDefaults(
  defineProps<{ variant?: "hero" | "list" | "detail" | "map" }>(),
  { variant: "list" },
);
</script>
<template>
  <view class="page-skeleton" aria-busy="true" aria-label="正在加载">
    <template v-if="variant === 'hero'">
      <view class="card skeleton-hero">
        <Skeleton width="28%" height="12px" />
        <Skeleton width="42%" height="40px" radius="8px" />
        <view class="skeleton-stats">
          <Skeleton v-for="i in 3" :key="i" height="44px" radius="8px" />
        </view>
        <Skeleton width="60%" height="14px" />
      </view>
      <view class="card skeleton-list">
        <Skeleton width="32%" height="18px" />
        <view v-for="i in 3" :key="i" class="skeleton-row">
          <Skeleton width="40px" height="40px" radius="6px" />
          <view class="skeleton-row-text">
            <Skeleton width="55%" height="14px" />
            <Skeleton width="38%" height="12px" />
          </view>
        </view>
      </view>
    </template>

    <view v-else-if="variant === 'detail'">
      <view class="card skeleton-hero">
        <Skeleton width="36%" height="12px" />
        <Skeleton width="58%" height="28px" radius="8px" />
        <view class="skeleton-detail-tiles">
          <Skeleton v-for="i in 4" :key="i" height="64px" radius="8px" />
        </view>
      </view>
      <view class="card skeleton-list">
        <Skeleton class="skeleton-map" width="100%" height="220px" radius="8px" />
        <Skeleton width="100%" height="120px" radius="8px" />
      </view>
    </view>

    <view v-else-if="variant === 'map'" class="card skeleton-map-card">
      <Skeleton width="100%" height="320px" radius="8px" />
      <Skeleton width="46%" height="14px" />
    </view>

    <view v-else class="card skeleton-list">
      <Skeleton width="30%" height="18px" />
      <view v-for="i in 5" :key="i" class="skeleton-row">
        <Skeleton width="36px" height="36px" radius="6px" />
        <view class="skeleton-row-text">
          <Skeleton :width="i % 2 ? '62%' : '48%'" height="14px" />
          <Skeleton :width="i % 2 ? '40%' : '56%'" height="12px" />
        </view>
      </view>
    </view>
  </view>
</template>
