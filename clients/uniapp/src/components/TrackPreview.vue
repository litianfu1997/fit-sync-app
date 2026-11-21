<script setup lang="ts">
import {
  getCurrentInstance,
  nextTick,
  onBeforeUnmount,
  onMounted,
  watch,
} from "vue";
import { themeColors, theme } from "../lib/theme";
const props = defineProps<{ points: { lat: number; lon: number }[] }>();
const instance = getCurrentInstance();
let disposed = false;
let resizeTimer: ReturnType<typeof setTimeout> | undefined;
let resizeStartedAt = 0;
function redrawAfterResize() {
  const now = Date.now();
  if (!resizeStartedAt) resizeStartedAt = now;
  if (resizeTimer) clearTimeout(resizeTimer);
  // 等待画布与布局更新；持续拖动窗口时最长 400ms 也会重画一次。
  const delay = Math.min(120, Math.max(0, 400 - (now - resizeStartedAt)));
  resizeTimer = setTimeout(() => {
    resizeTimer = undefined;
    resizeStartedAt = 0;
    draw();
  }, delay);
}
function draw() {
  nextTick(() => {
    if (disposed) return;
    uni
      .createSelectorQuery()
      .in(instance?.proxy)
      .select("#routeCanvas")
      .boundingClientRect((result) => {
        const box = Array.isArray(result) ? result[0] : result;
        if (disposed || !box?.width) return;
        const w = box.width,
          h = 240,
          pad = 28,
          ctx = uni.createCanvasContext("routeCanvas", instance?.proxy);
        ctx.setFillStyle(themeColors.value["surface-high"]);
        ctx.fillRect(0, 0, w, h);
        // 预览限制采样数量，保留首尾，避免超大 FIT 轨迹阻塞页面。
        const step = Math.max(1, Math.ceil(props.points.length / 3000));
        const valid = props.points.filter(
          (p, i) =>
            Number.isFinite(p.lat) &&
            Number.isFinite(p.lon) &&
            (i % step === 0 || i === props.points.length - 1),
        );
        if (valid.length < 2) {
          ctx.draw();
          return;
        }
        const cosine = Math.cos((valid[0].lat * Math.PI) / 180),
          xs = valid.map((p) => p.lon * cosine),
          ys = valid.map((p) => p.lat);
        const xmin = Math.min(...xs),
          xmax = Math.max(...xs),
          ymin = Math.min(...ys),
          ymax = Math.max(...ys);
        const scale = Math.min(
          (w - 2 * pad) / (xmax - xmin || 0.0001),
          (h - 2 * pad) / (ymax - ymin || 0.0001),
        );
        const px = (i: number) =>
          (w - (xmax - xmin) * scale) / 2 + (xs[i] - xmin) * scale;
        const py = (i: number) =>
          (h - (ymax - ymin) * scale) / 2 + (ymax - ys[i]) * scale;
        ctx.beginPath();
        ctx.setStrokeStyle(themeColors.value.muted);
        ctx.setLineWidth(1.5);
        ctx.setLineCap("round");
        valid.forEach((_, i) =>
          i ? ctx.lineTo(px(i), py(i)) : ctx.moveTo(px(i), py(i)),
        );
        ctx.stroke();
        ctx.draw();
      })
      .exec();
  });
}
onMounted(() => {
  draw();
  uni.onWindowResize(redrawAfterResize);
});
onBeforeUnmount(() => {
  disposed = true;
  if (resizeTimer) clearTimeout(resizeTimer);
  uni.offWindowResize(redrawAfterResize);
});
watch(() => props.points, draw);
watch(() => theme.dark, draw);
</script>
<template>
  <view class="track-preview"
    ><canvas
      id="routeCanvas"
      canvas-id="routeCanvas"
      style="width: 100%; height: 240px"
    /><text class="track-caption"
      >轨迹简图 · 仅用于查看路线形状，不含地图底图</text
    ></view
  >
</template>
