<script setup lang="ts">
import {
  computed,
  getCurrentInstance,
  nextTick,
  onBeforeUnmount,
  onMounted,
  watch,
} from "vue";
import { theme, themeColors } from "../lib/theme";

const props = defineProps<{
  title: string;
  unit: string;
  values: number[];
  tone: "info" | "muted";
}>();

const instance = getCurrentInstance();
const canvasId = `series-chart-${Math.random().toString(36).slice(2, 9)}`;

const peakText = computed(() => {
  if (!props.values.length) return "";
  let peak = -Infinity;
  for (const v of props.values) {
    if (Number.isFinite(v) && v > peak) peak = v;
  }
  return Number.isFinite(peak) ? `最大 ${peak.toFixed(1)} ${props.unit}` : "";
});

function hexToRgba(hex: string, alpha: number): string {
  const clean = hex.replace("#", "");
  if (clean.length !== 6) return hex;
  const r = parseInt(clean.slice(0, 2), 16);
  const g = parseInt(clean.slice(2, 4), 16);
  const b = parseInt(clean.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

function draw() {
  const raw = props.values.filter((v) => Number.isFinite(v));
  // 长活动采样点可达数万，按画布宽度抽稀，避免每次重绘全量描边。
  const step = Math.max(1, Math.ceil(raw.length / 1500));
  const pts =
    step > 1
      ? raw.filter((_, i) => i % step === 0 || i === raw.length - 1)
      : raw;
  if (pts.length < 2) return;
  const query = uni.createSelectorQuery().in(instance?.proxy);
  query
    .select(`#${canvasId}`)
    .boundingClientRect((rect) => {
      const box = Array.isArray(rect) ? rect[0] : rect;
      if (!box || !box.width || !box.height) return;
      const w = box.width;
      const h = box.height;
      const padX = 4;
      const padY = 8;

      let minV = Infinity;
      let maxV = -Infinity;
      for (const v of pts) {
        if (v < minV) minV = v;
        if (v > maxV) maxV = v;
      }
      const span = Math.max(maxV - minV, 1e-3);

      const strokeColor =
        props.tone === "info" ? themeColors.value.info : themeColors.value.muted;
      const fillColor = hexToRgba(strokeColor, theme.dark ? 0.22 : 0.14);

      const ctx = uni.createCanvasContext(canvasId, instance?.proxy);
      ctx.clearRect(0, 0, w, h);

      const coords = pts.map((v, idx) => {
        const x = padX + (idx / (pts.length - 1)) * (w - padX * 2);
        const norm = (v - minV) / span;
        const y = h - padY - norm * (h - padY * 2);
        return { x, y };
      });

      // 区域填充
      ctx.beginPath();
      ctx.moveTo(coords[0].x, h - padY + 4);
      for (const p of coords) {
        ctx.lineTo(p.x, p.y);
      }
      ctx.lineTo(coords[coords.length - 1].x, h - padY + 4);
      ctx.closePath();
      ctx.setFillStyle(fillColor);
      ctx.fill();

      // 折线描边
      ctx.beginPath();
      ctx.moveTo(coords[0].x, coords[0].y);
      for (let i = 1; i < coords.length; i++) {
        ctx.lineTo(coords[i].x, coords[i].y);
      }
      ctx.setStrokeStyle(strokeColor);
      ctx.setLineWidth(2);
      ctx.setLineCap("round");
      ctx.setLineJoin("round");
      ctx.stroke();
      ctx.draw();
    })
    .exec();
}

function handleResize() {
  nextTick(draw);
}

onMounted(() => {
  nextTick(draw);
  uni.onWindowResize?.(handleResize);
});

onBeforeUnmount(() => {
  uni.offWindowResize?.(handleResize);
});

watch(
  () => [props.values, props.tone, theme.dark] as const,
  () => nextTick(draw),
  { deep: false },
);
</script>

<template>
  <view v-if="values.length >= 2" class="series-chart-block">
    <view class="series-chart-header">
      <text class="series-chart-title">{{ title }}</text>
      <text class="muted tiny">{{ peakText }}</text>
    </view>
    <view class="series-chart-surface">
      <canvas :id="canvasId" :canvas-id="canvasId" class="series-chart-canvas" />
    </view>
  </view>
</template>
