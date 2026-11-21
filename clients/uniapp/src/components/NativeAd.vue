<script setup lang="ts">
import { ref } from "vue";
import { session } from "../lib/api";
const failed = ref(false);
</script>
<template>
  <!-- #ifdef MP-WEIXIN -->
  <view
    v-if="
      !failed &&
      session.config?.ads.enabled &&
      session.config.ads.nativeAdUnitId
    "
    class="ad-card"
    ><text class="ad-caption">广告 · 支持 FitSync 免费运营</text
    ><ad-custom
      :unit-id="session.config.ads.nativeAdUnitId"
      @error="failed = true"
  /></view>
  <!-- #endif -->
</template>
