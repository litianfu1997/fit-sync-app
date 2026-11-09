<script setup lang="ts">
import { ref } from "vue";
import { session, toast } from "../lib/api";
const busy = ref(false);
function showVideo() {
  // #ifdef MP-WEIXIN
  const adUnitId = session.config?.ads.rewardedAdUnitId;
  if (!adUnitId || busy.value) return;
  busy.value = true;
  const ad = uni.createRewardedVideoAd({ adUnitId });
  const clean = () => {
    busy.value = false;
    ad.offClose(onClose);
    ad.offError(onError);
    ad.destroy();
  };
  const onClose = (result: { isEnded?: boolean }) => {
    toast(
      result?.isEnded
        ? "感谢支持，免费同步服务将继续运行。"
        : "已关闭视频，你可以继续正常使用。",
    );
    clean();
  };
  const onError = () => {
    toast("暂时没有可展示的广告，请稍后再试。");
    clean();
  };
  ad.onClose(onClose);
  ad.onError(onError);
  ad.load()
    .then(() => ad.show())
    .catch(onError);
  // #endif
}
</script>
<template>
  <!-- #ifdef MP-WEIXIN -->
  <view v-if="session.config?.ads.rewardedAdUnitId" class="support-card"
    ><view
      ><text class="section-title">让免费服务跑得更远</text
      ><text class="muted"
        >自愿观看视频支持运营，基础同步免费。不承诺额外额度。</text
      ></view
    ><button
      class="btn secondary"
      :disabled="busy"
      :loading="busy"
      @click="showVideo"
    >
      自愿支持
    </button></view
  >
  <!-- #endif -->
</template>
