export interface GeoPoint {
  lat: number;
  lon: number;
}

export const MAP_TILE_SIZE = 256;
export const MAP_MIN_ZOOM = 3;
export const MAP_MAX_ZOOM = 19;
export const MAP_SINGLE_POINT_ZOOM = 15;
export const MAP_BOUNDS_PADDING_SCALE = 1.18;

const MAX_MERCATOR_LAT = 85.05112878;
const GCJ_A = 6378245.0;
const GCJ_EE = 0.00669342162296594323;

export function filterValidPoints(points?: GeoPoint[] | null): GeoPoint[] {
  if (!points || !points.length) return [];
  const valid: GeoPoint[] = [];
  for (const p of points) {
    if (
      p &&
      Number.isFinite(p.lat) &&
      Number.isFinite(p.lon) &&
      Math.abs(p.lat) <= 90 &&
      Math.abs(p.lon) <= 180 &&
      !(Math.abs(p.lat) < 1e-6 && Math.abs(p.lon) < 1e-6)
    ) {
      valid.push({ lat: p.lat, lon: p.lon });
    }
  }
  return valid;
}

/**
 * 均匀抽稀轨迹点并强制保留首尾点，避免原生地图组件在长轨迹下渲染卡顿。
 */
export function downsamplePoints(points: GeoPoint[], maxPoints: number): GeoPoint[] {
  if (maxPoints < 2 || points.length <= maxPoints) return points;
  const result: GeoPoint[] = [];
  const step = (points.length - 1) / (maxPoints - 1);
  for (let i = 0; i < maxPoints - 1; i++) {
    result.push(points[Math.round(i * step)]);
  }
  result.push(points[points.length - 1]);
  return result;
}

export function clampLat(lat: number): number {
  return Math.max(-MAX_MERCATOR_LAT, Math.min(MAX_MERCATOR_LAT, lat));
}

export function normalizeLon(lon: number): number {
  let v = lon;
  while (v > 180) v -= 360;
  while (v < -180) v += 360;
  return v;
}

export function lonToWorldX(lon: number, zoom: number): number {
  const scale = MAP_TILE_SIZE * Math.pow(2, zoom);
  return ((lon + 180) / 360) * scale;
}

export function latToWorldY(lat: number, zoom: number): number {
  const clamped = clampLat(lat);
  const sin = Math.sin((clamped * Math.PI) / 180);
  const y = 0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI);
  const scale = MAP_TILE_SIZE * Math.pow(2, zoom);
  return y * scale;
}

export function worldXToLon(x: number, zoom: number): number {
  const scale = MAP_TILE_SIZE * Math.pow(2, zoom);
  return normalizeLon((x / scale) * 360 - 180);
}

export function worldYToLat(y: number, zoom: number): number {
  const scale = MAP_TILE_SIZE * Math.pow(2, zoom);
  const norm = Math.max(0, Math.min(1, y / scale));
  const n = Math.PI - 2 * Math.PI * norm;
  return clampLat((180 / Math.PI) * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n))));
}

/**
 * 依据 Android TrackMap 的 1.18 倍边距比例计算适配视口中心与缩放级别。
 */
export function fitTrackViewport(
  points: GeoPoint[],
  width: number,
  height: number,
): { centerLat: number; centerLon: number; zoom: number } {
  if (!points.length) {
    return { centerLat: 31.2304, centerLon: 121.4737, zoom: MAP_MIN_ZOOM };
  }
  let minX = Infinity;
  let maxX = -Infinity;
  let minY = Infinity;
  let maxY = -Infinity;
  for (const p of points) {
    const wx = lonToWorldX(p.lon, 0);
    const wy = latToWorldY(p.lat, 0);
    if (wx < minX) minX = wx;
    if (wx > maxX) maxX = wx;
    if (wy < minY) minY = wy;
    if (wy > maxY) maxY = wy;
  }
  const centerX = (minX + maxX) / 2;
  const centerY = (minY + maxY) / 2;
  const centerLat = worldYToLat(centerY, 0);
  const centerLon = worldXToLon(centerX, 0);

  const spanX = maxX - minX;
  const spanY = maxY - minY;
  if (spanX < 1e-7 && spanY < 1e-7) {
    return { centerLat, centerLon, zoom: MAP_SINGLE_POINT_ZOOM };
  }

  const availW = Math.max(width, 64) / MAP_BOUNDS_PADDING_SCALE;
  const availH = Math.max(height, 64) / MAP_BOUNDS_PADDING_SCALE;
  const zoomX = spanX > 1e-7 ? Math.log2(availW / spanX) : MAP_MAX_ZOOM;
  const zoomY = spanY > 1e-7 ? Math.log2(availH / spanY) : MAP_MAX_ZOOM;
  const zoom = Math.max(MAP_MIN_ZOOM, Math.min(MAP_MAX_ZOOM, Math.min(zoomX, zoomY)));
  return { centerLat, centerLon, zoom };
}

function outOfChina(lat: number, lon: number): boolean {
  return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
}

function transformLat(x: number, y: number): number {
  let ret =
    -100.0 +
    2.0 * x +
    3.0 * y +
    0.2 * y * y +
    0.1 * x * y +
    0.2 * Math.sqrt(Math.abs(x));
  ret +=
    ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0;
  ret += ((20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin((y / 3.0) * Math.PI)) * 2.0) / 3.0;
  ret +=
    ((160.0 * Math.sin((y / 12.0) * Math.PI) + 320 * Math.sin((y * Math.PI) / 30.0)) * 2.0) / 3.0;
  return ret;
}

function transformLon(x: number, y: number): number {
  let ret =
    300.0 +
    x +
    2.0 * y +
    0.1 * x * x +
    0.1 * x * y +
    0.1 * Math.sqrt(Math.abs(x));
  ret +=
    ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0;
  ret += ((20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin((x / 3.0) * Math.PI)) * 2.0) / 3.0;
  ret +=
    ((150.0 * Math.sin((x / 12.0) * Math.PI) + 300.0 * Math.sin((x / 30.0) * Math.PI)) * 2.0) /
    3.0;
  return ret;
}

/**
 * 微信小程序原生 <map> 在中国大陆使用 GCJ-02 坐标系；FIT 原始坐标为 WGS-84，需转换以避免约 500m 偏移。
 */
export function wgs84ToGcj02(lat: number, lon: number): GeoPoint {
  if (outOfChina(lat, lon)) return { lat, lon };
  let dLat = transformLat(lon - 105.0, lat - 35.0);
  let dLon = transformLon(lon - 105.0, lat - 35.0);
  const radLat = (lat / 180.0) * Math.PI;
  let magic = Math.sin(radLat);
  magic = 1 - GCJ_EE * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  dLat = (dLat * 180.0) / (((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic)) * Math.PI);
  dLon = (dLon * 180.0) / ((GCJ_A / sqrtMagic) * Math.cos(radLat) * Math.PI);
  return { lat: lat + dLat, lon: lon + dLon };
}

export function convertPointsToGcj02(points: GeoPoint[]): GeoPoint[] {
  return points.map((p) => wgs84ToGcj02(p.lat, p.lon));
}
