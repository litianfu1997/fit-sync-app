<script setup lang="ts">
import {
  computed,
  getCurrentInstance,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import {
  type GeoPoint,
  MAP_MAX_ZOOM,
  MAP_MIN_ZOOM,
  MAP_TILE_SIZE,
  clampLat,
  convertPointsToGcj02,
  downsamplePoints,
  filterValidPoints,
  fitTrackViewport,
  latToWorldY,
  lonToWorldX,
  worldXToLon,
  worldYToLat,
} from "../lib/geo";
import { theme, themeColors } from "../lib/theme";

const props = withDefaults(
  defineProps<{
    points?: GeoPoint[] | null;
    interactive?: boolean;
    height?: string;
  }>(),
  {
    points: () => [],
    interactive: false,
    height: "200px",
  },
);

const emit = defineEmits<{ (e: "tap"): void }>();

const uid = Math.random().toString(36).slice(2, 9);
const validPoints = computed(() => filterValidPoints(props.points));
const containerStyle = computed(() => ({ height: props.height }));

// #ifdef H5
interface TileCacheEntry {
  status: "loading" | "ready" | "error";
  img: HTMLImageElement;
}

const MAX_TILE_CACHE = 400;
const h5HostId = `track-map-host-${uid}`;
let h5TileCache: Map<string, TileCacheEntry> | null = null;
function getTileCache(): Map<string, TileCacheEntry> {
  if (!h5TileCache) {
    const w = window as unknown as { __fitsyncOsmTileCache?: Map<string, TileCacheEntry> };
    if (!w.__fitsyncOsmTileCache) {
      w.__fitsyncOsmTileCache = new Map<string, TileCacheEntry>();
    }
    h5TileCache = w.__fitsyncOsmTileCache;
  }
  return h5TileCache;
}

const viewLat = ref(31.2304);
const viewLon = ref(121.4737);
const viewZoom = ref(MAP_MIN_ZOOM);
const hasUserViewport = ref(false);
const hasAnyTileReady = ref(false);

let hostEl: HTMLElement | null = null;
let canvasEl: HTMLCanvasElement | null = null;
let resizeObserver: ResizeObserver | null = null;
let rafId: number | null = null;
let viewW = 0;
let viewH = 0;

const activePointers = new Map<number, { x: number; y: number }>();
let lastPanPoint: { x: number; y: number } | null = null;
let lastPinchDist = 0;

function requestTile(z: number, x: number, y: number): HTMLImageElement | null {
  const cache = getTileCache();
  const key = `${z}/${x}/${y}`;
  const existing = cache.get(key);
  if (existing) {
    if (existing.status === "ready") {
      hasAnyTileReady.value = true;
      return existing.img;
    }
    return null;
  }
  if (cache.size >= MAX_TILE_CACHE) {
    const oldestKey = cache.keys().next().value;
    if (oldestKey) cache.delete(oldestKey);
  }
  const img = new Image();
  const entry: TileCacheEntry = { status: "loading", img };
  cache.set(key, entry);
  img.onload = () => {
    entry.status = "ready";
    hasAnyTileReady.value = true;
    scheduleDraw();
  };
  img.onerror = () => {
    entry.status = "error";
  };
  img.src = `https://tile.openstreetmap.org/${z}/${x}/${y}.png`;
  return null;
}

function fitToTrack(resetUserFlag = true) {
  const pts = validPoints.value;
  if (!pts.length || viewW <= 0 || viewH <= 0) return;
  const fitted = fitTrackViewport(pts, viewW, viewH);
  viewLat.value = fitted.centerLat;
  viewLon.value = fitted.centerLon;
  viewZoom.value = fitted.zoom;
  if (resetUserFlag) hasUserViewport.value = false;
  scheduleDraw();
}

function zoomBy(delta: number, anchorX?: number, anchorY?: number) {
  if (!props.interactive || viewW <= 0 || viewH <= 0) return;
  const nextZoom = Math.max(MAP_MIN_ZOOM, Math.min(MAP_MAX_ZOOM, viewZoom.value + delta));
  if (Math.abs(nextZoom - viewZoom.value) < 1e-4) return;
  hasUserViewport.value = true;

  if (anchorX !== undefined && anchorY !== undefined) {
    const oldZ = viewZoom.value;
    const oldOriginX = lonToWorldX(viewLon.value, oldZ) - viewW / 2;
    const oldOriginY = latToWorldY(viewLat.value, oldZ) - viewH / 2;
    const anchorLon = worldXToLon(oldOriginX + anchorX, oldZ);
    const anchorLat = worldYToLat(oldOriginY + anchorY, oldZ);
    const newCenterWX = lonToWorldX(anchorLon, nextZoom) + (viewW / 2 - anchorX);
    const newCenterWY = latToWorldY(anchorLat, nextZoom) + (viewH / 2 - anchorY);
    viewLon.value = worldXToLon(newCenterWX, nextZoom);
    viewLat.value = worldYToLat(newCenterWY, nextZoom);
  }
  viewZoom.value = nextZoom;
  scheduleDraw();
}

function h5ZoomIn() {
  zoomBy(1);
}

function h5ZoomOut() {
  zoomBy(-1);
}

function h5Recenter() {
  fitToTrack(true);
}

function drawFrame() {
  rafId = null;
  if (!canvasEl || !hostEl) return;
  const rect = hostEl.getBoundingClientRect();
  const w = Math.round(rect.width);
  const h = Math.round(rect.height);
  if (w <= 0 || h <= 0) return;
  viewW = w;
  viewH = h;

  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const targetW = Math.round(w * dpr);
  const targetH = Math.round(h * dpr);
  if (canvasEl.width !== targetW || canvasEl.height !== targetH) {
    canvasEl.width = targetW;
    canvasEl.height = targetH;
  }
  const ctx = canvasEl.getContext("2d");
  if (!ctx) return;

  const colors = themeColors.value;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  ctx.fillStyle = colors["surface-high"];
  ctx.fillRect(0, 0, w, h);

  const pts = validPoints.value;
  if (!pts.length) return;

  const zoom = viewZoom.value;
  const baseZoom = Math.max(MAP_MIN_ZOOM, Math.min(MAP_MAX_ZOOM, Math.round(zoom)));
  const tileScale = Math.pow(2, zoom - baseZoom);
  const drawTileSize = MAP_TILE_SIZE * tileScale;
  const centerWX = lonToWorldX(viewLon.value, zoom);
  const centerWY = latToWorldY(viewLat.value, zoom);
  const originWX = centerWX - w / 2;
  const originWY = centerWY - h / 2;

  // 1. 绘制 OSM Mapnik 栅格瓦片（离线或失败时保持 surface-high 底色，与 Android 一致）
  const maxTiles = Math.pow(2, baseZoom);
  const txStart = Math.floor(originWX / drawTileSize);
  const txEnd = Math.floor((originWX + w) / drawTileSize);
  const tyStart = Math.max(0, Math.floor(originWY / drawTileSize));
  const tyEnd = Math.min(maxTiles - 1, Math.floor((originWY + h) / drawTileSize));

  ctx.save();
  if (theme.dark) {
    ctx.globalAlpha = 0.78;
  }
  for (let ty = tyStart; ty <= tyEnd; ty++) {
    for (let tx = txStart; tx <= txEnd; tx++) {
      const wrappedX = ((tx % maxTiles) + maxTiles) % maxTiles;
      const dx = tx * drawTileSize - originWX;
      const dy = ty * drawTileSize - originWY;
      const tileImg = requestTile(baseZoom, wrappedX, ty);
      if (tileImg) {
        ctx.drawImage(tileImg, dx, dy, drawTileSize + 0.5, drawTileSize + 0.5);
      } else if (baseZoom > MAP_MIN_ZOOM) {
        // 用父层级已缓存瓦片的对应象限垫底，避免缩放瞬间闪烁
        const pz = baseZoom - 1;
        const pMax = Math.pow(2, pz);
        const px = (((Math.floor(tx / 2) % pMax) + pMax) % pMax);
        const py = Math.floor(ty / 2);
        const parentCache = getTileCache().get(`${pz}/${px}/${py}`);
        if (parentCache?.status === "ready") {
          const sx = (tx & 1) * 128;
          const sy = (ty & 1) * 128;
          ctx.drawImage(parentCache.img, sx, sy, 128, 128, dx, dy, drawTileSize + 0.5, drawTileSize + 0.5);
        }
      }
    }
  }
  ctx.restore();

  if (theme.dark && hasAnyTileReady.value) {
    ctx.fillStyle = "rgba(10, 14, 19, 0.22)";
    ctx.fillRect(0, 0, w, h);
  }

  // 2. 绘制路线折线（primary 橙色，3px 圆角端点与连接）
  if (pts.length >= 2) {
    ctx.save();
    ctx.strokeStyle = colors.primary;
    ctx.lineWidth = 3;
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    ctx.beginPath();
    let prevX = lonToWorldX(pts[0].lon, zoom) - originWX;
    let prevY = latToWorldY(pts[0].lat, zoom) - originWY;
    ctx.moveTo(prevX, prevY);
    for (let i = 1; i < pts.length; i++) {
      const sx = lonToWorldX(pts[i].lon, zoom) - originWX;
      const sy = latToWorldY(pts[i].lat, zoom) - originWY;
      if (i === pts.length - 1 || Math.abs(sx - prevX) >= 0.6 || Math.abs(sy - prevY) >= 0.6) {
        ctx.lineTo(sx, sy);
        prevX = sx;
        prevY = sy;
      }
    }
    ctx.stroke();
    ctx.restore();
  }

  // 3. 绘制起点与终点圆点（6px 半径 + 2px 白色外描边，对齐 Android TrackMap）
  const drawMarker = (pt: GeoPoint, fillColor: string) => {
    const sx = lonToWorldX(pt.lon, zoom) - originWX;
    const sy = latToWorldY(pt.lat, zoom) - originWY;
    if (sx < -20 || sx > w + 20 || sy < -20 || sy > h + 20) return;
    ctx.save();
    ctx.beginPath();
    ctx.arc(sx, sy, 6, 0, Math.PI * 2);
    ctx.fillStyle = fillColor;
    ctx.fill();
    ctx.lineWidth = 2;
    ctx.strokeStyle = "#FFFFFF";
    ctx.stroke();
    ctx.restore();
  };

  drawMarker(pts[0], colors.info);
  if (pts.length >= 2) {
    drawMarker(pts[pts.length - 1], colors.error);
  }
}

function scheduleDraw() {
  if (rafId !== null) return;
  rafId = window.requestAnimationFrame(drawFrame);
}

function handlePointerDown(e: PointerEvent) {
  if (!props.interactive || !canvasEl) return;
  canvasEl.setPointerCapture?.(e.pointerId);
  activePointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (activePointers.size === 1) {
    lastPanPoint = { x: e.clientX, y: e.clientY };
    lastPinchDist = 0;
  } else if (activePointers.size === 2) {
    const iter = Array.from(activePointers.values());
    lastPinchDist = Math.hypot(iter[0].x - iter[1].x, iter[0].y - iter[1].y);
    lastPanPoint = null;
  }
}

function handlePointerMove(e: PointerEvent) {
  if (!props.interactive || !activePointers.has(e.pointerId) || !hostEl) return;
  activePointers.set(e.pointerId, { x: e.clientX, y: e.clientY });

  if (activePointers.size === 1 && lastPanPoint) {
    const dx = e.clientX - lastPanPoint.x;
    const dy = e.clientY - lastPanPoint.y;
    lastPanPoint = { x: e.clientX, y: e.clientY };
    if (Math.abs(dx) < 0.01 && Math.abs(dy) < 0.01) return;
    hasUserViewport.value = true;
    const z = viewZoom.value;
    const nextWX = lonToWorldX(viewLon.value, z) - dx;
    const nextWY = latToWorldY(viewLat.value, z) - dy;
    viewLon.value = worldXToLon(nextWX, z);
    viewLat.value = clampLat(worldYToLat(nextWY, z));
    scheduleDraw();
  } else if (activePointers.size === 2) {
    const pts = Array.from(activePointers.values());
    const dist = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
    if (lastPinchDist > 10 && dist > 10) {
      const rect = hostEl.getBoundingClientRect();
      const midX = (pts[0].x + pts[1].x) / 2 - rect.left;
      const midY = (pts[0].y + pts[1].y) / 2 - rect.top;
      zoomBy(Math.log2(dist / lastPinchDist), midX, midY);
    }
    lastPinchDist = dist;
  }
}

function handlePointerUpOrCancel(e: PointerEvent) {
  if (!activePointers.has(e.pointerId)) return;
  activePointers.delete(e.pointerId);
  if (activePointers.size === 1) {
    const only = Array.from(activePointers.values())[0];
    lastPanPoint = { x: only.x, y: only.y };
    lastPinchDist = 0;
  } else {
    lastPanPoint = null;
    lastPinchDist = 0;
  }
}

function handleWheel(e: WheelEvent) {
  if (!props.interactive || !hostEl) return;
  e.preventDefault();
  const rect = hostEl.getBoundingClientRect();
  const mx = e.clientX - rect.left;
  const my = e.clientY - rect.top;
  const delta = Math.abs(e.deltaY) < 40 ? -e.deltaY * 0.012 : e.deltaY < 0 ? 0.6 : -0.6;
  zoomBy(delta, mx, my);
}

function setupH5Canvas() {
  const el = document.getElementById(h5HostId);
  if (!el) return;
  hostEl = el;
  if (!canvasEl) {
    canvasEl = document.createElement("canvas");
    canvasEl.className = "track-map-h5-canvas";
    canvasEl.style.width = "100%";
    canvasEl.style.height = "100%";
    canvasEl.style.display = "block";
    canvasEl.style.touchAction = props.interactive ? "none" : "pan-y";
    el.appendChild(canvasEl);

    canvasEl.addEventListener("pointerdown", handlePointerDown);
    canvasEl.addEventListener("pointermove", handlePointerMove);
    canvasEl.addEventListener("pointerup", handlePointerUpOrCancel);
    canvasEl.addEventListener("pointercancel", handlePointerUpOrCancel);
    canvasEl.addEventListener("wheel", handleWheel, { passive: false });
  }
  const rect = el.getBoundingClientRect();
  viewW = Math.round(rect.width);
  viewH = Math.round(rect.height);
  fitToTrack(true);

  if (typeof ResizeObserver !== "undefined" && !resizeObserver) {
    resizeObserver = new ResizeObserver((entries) => {
      const box = entries[0]?.contentRect;
      if (!box || box.width <= 0 || box.height <= 0) return;
      const nw = Math.round(box.width);
      const nh = Math.round(box.height);
      const changed = nw !== viewW || nh !== viewH;
      viewW = nw;
      viewH = nh;
      if (!hasUserViewport.value) {
        fitToTrack(true);
      } else if (changed) {
        scheduleDraw();
      }
    });
    resizeObserver.observe(el);
  }
}

onMounted(() => {
  nextTick(() => setupH5Canvas());
});

onBeforeUnmount(() => {
  if (rafId !== null) window.cancelAnimationFrame(rafId);
  resizeObserver?.disconnect();
  if (canvasEl) {
    canvasEl.removeEventListener("pointerdown", handlePointerDown);
    canvasEl.removeEventListener("pointermove", handlePointerMove);
    canvasEl.removeEventListener("pointerup", handlePointerUpOrCancel);
    canvasEl.removeEventListener("pointercancel", handlePointerUpOrCancel);
    canvasEl.removeEventListener("wheel", handleWheel);
    canvasEl.remove();
  }
  canvasEl = null;
  hostEl = null;
});

watch(
  () => [props.points, props.interactive] as const,
  () => {
    if (canvasEl) {
      canvasEl.style.touchAction = props.interactive ? "none" : "pan-y";
      fitToTrack(true);
    }
  },
  { deep: false },
);

watch(
  () => [theme.dark, themeColors.value.primary] as const,
  () => scheduleDraw(),
);

function onH5Tap() {
  if (!props.interactive) emit("tap");
}
// #endif

// #ifdef MP-WEIXIN
const mpMapId = `track-map-mp-${uid}`;
const instance = getCurrentInstance();

const gcjTrack = computed(() => convertPointsToGcj02(validPoints.value));
const mpPolylinePoints = computed(() => downsamplePoints(gcjTrack.value, 360));
const mpIncludePoints = computed(() =>
  downsamplePoints(gcjTrack.value, 64).map((p) => ({
    latitude: p.lat,
    longitude: p.lon,
  })),
);
const mpCenter = computed(() => {
  const pts = mpIncludePoints.value;
  if (!pts.length) return { lat: 31.2304, lon: 121.4737 };
  let minLat = Infinity;
  let maxLat = -Infinity;
  let minLon = Infinity;
  let maxLon = -Infinity;
  for (const p of pts) {
    if (p.latitude < minLat) minLat = p.latitude;
    if (p.latitude > maxLat) maxLat = p.latitude;
    if (p.longitude < minLon) minLon = p.longitude;
    if (p.longitude > maxLon) maxLon = p.longitude;
  }
  return { lat: (minLat + maxLat) / 2, lon: (minLon + maxLon) / 2 };
});
const mpPolyline = computed(() => {
  const pts = mpPolylinePoints.value;
  if (pts.length < 2) return [];
  return [
    {
      points: pts.map((p) => ({ latitude: p.lat, longitude: p.lon })),
      color: themeColors.value.primary,
      width: 4,
      arrowLine: false,
      borderColor: "#FFFFFF",
      borderWidth: 1,
    },
  ];
});
const mpMarkers = computed(() => {
  const pts = gcjTrack.value;
  if (!pts.length) return [];
  const start = pts[0];
  const result = [
    {
      id: 1,
      latitude: start.lat,
      longitude: start.lon,
      iconPath: "/static/marker-start.png",
      width: 16,
      height: 16,
      anchor: { x: 0.5, y: 0.5 },
    },
  ];
  if (pts.length >= 2) {
    const end = pts[pts.length - 1];
    result.push({
      id: 2,
      latitude: end.lat,
      longitude: end.lon,
      iconPath: "/static/marker-end.png",
      width: 16,
      height: 16,
      anchor: { x: 0.5, y: 0.5 },
    });
  }
  return result;
});

function mpRecenter() {
  const pts = mpIncludePoints.value;
  if (!pts.length) return;
  const ctx = uni.createMapContext(mpMapId, instance?.proxy);
  ctx?.includePoints({
    points: pts,
    padding: [36, 36, 36, 36],
  });
}

function onMpTap() {
  if (!props.interactive) emit("tap");
}
// #endif
</script>

<template>
  <view
    class="track-map-wrap"
    :class="{ 'track-map-dark': theme.dark, 'track-map-fullscreen': interactive }"
    :style="containerStyle"
  >
    <!-- #ifdef H5 -->
    <view :id="h5HostId" class="track-map-h5-host" :class="{ interactive }" @click="onH5Tap" />
    <view v-if="validPoints.length" class="track-map-attribution">
      <text>© OpenStreetMap contributors</text>
    </view>
    <view v-if="!interactive && validPoints.length" class="track-map-open-hint" @click.stop="onH5Tap">
      <text>全屏地图</text>
    </view>
    <view v-if="interactive && validPoints.length" class="track-map-controls" @click.stop>
      <button class="track-map-ctrl-btn" aria-label="放大地图" @click.stop="h5ZoomIn">+</button>
      <button class="track-map-ctrl-btn" aria-label="缩小地图" @click.stop="h5ZoomOut">−</button>
      <button class="track-map-ctrl-btn track-map-ctrl-text" aria-label="居中轨迹" @click.stop="h5Recenter">居中</button>
    </view>
    <!-- #endif -->

    <!-- #ifdef MP-WEIXIN -->
    <map
      :id="mpMapId"
      class="track-map-native"
      :longitude="mpCenter.lon"
      :latitude="mpCenter.lat"
      :polyline="mpPolyline"
      :markers="mpMarkers"
      :include-points="mpIncludePoints"
      :enable-zoom="interactive"
      :enable-scroll="interactive"
      :enable-rotate="false"
      :enable-overlooking="false"
      :show-compass="false"
      :show-scale="interactive"
    />
    <cover-view v-if="!interactive" class="track-map-mp-mask" @tap="onMpTap" />
    <cover-view v-if="!interactive && validPoints.length" class="track-map-mp-badge" @tap="onMpTap">
      <cover-view class="track-map-mp-badge-text">全屏地图</cover-view>
    </cover-view>
    <cover-view v-else-if="interactive && validPoints.length" class="track-map-mp-controls">
      <cover-view class="track-map-mp-ctrl-btn" @tap="mpRecenter">居中</cover-view>
    </cover-view>
    <!-- #endif -->
  </view>
</template>

<style>
/* #ifdef MP-WEIXIN */
:host {
  display: block;
  width: 100%;
}
/* #endif */
</style>
