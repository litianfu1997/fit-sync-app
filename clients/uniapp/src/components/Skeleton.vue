<script setup lang="ts">
withDefaults(
  defineProps<{
    width?: string;
    height?: string;
    radius?: string;
    circle?: boolean;
  }>(),
  { width: "100%", height: "16px", radius: "6px", circle: false },
);
</script>
<template>
  <view
    class="skeleton"
    :class="{ 'skeleton-circle': circle }"
    :style="{ width, height, borderRadius: circle ? '50%' : radius }"
  />
</template>
