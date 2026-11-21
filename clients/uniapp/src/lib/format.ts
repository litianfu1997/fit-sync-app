export const platformNames: Record<string, string> = {
  LOCAL: "本地 FIT",
  COROS: "COROS",
  IGPSPORT: "iGPSPORT",
  ONELAP: "迈金",
  XINGZHE: "行者",
  GARMIN: "Garmin",
  STRAVA: "Strava",
};
export const platformName = (code?: string) =>
  platformNames[code || ""] || code || "本地 FIT";
export const platformInitials: Record<string, string> = {
  LOCAL: "本", ONELAP: "迈", XINGZHE: "行", GARMIN: "G",
  COROS: "C", IGPSPORT: "iG", STRAVA: "S",
};
export const sportNames: Record<string, string> = {
  CYCLING: "骑行",
  INDOOR_CYCLING: "室内骑行",
  MTB: "山地骑行",
  GRAVEL: "砾石骑行",
  RUNNING: "跑步",
  OTHER: "其他运动",
  UNKNOWN: "骑行",
};
export const sportLabel = (sport?: string) =>
  sportNames[sport || ""] || "骑行";
export const sportCategoryOptions = [
  { code: "CYCLING", label: "骑行" },
  { code: "RUNNING", label: "跑步" },
  { code: "OTHER", label: "其他" },
] as const;
export function sportTypeLabels(sportTypes?: string[]): string[] {
  if (!sportTypes || !sportTypes.length) return [];
  const mapped = new Set<string>();
  for (const raw of sportTypes) {
    const token = String(raw || "").toUpperCase();
    if (["CYCLING", "INDOOR_CYCLING", "MTB", "GRAVEL"].includes(token)) mapped.add("CYCLING");
    else if (token === "RUNNING") mapped.add("RUNNING");
    else if (["OTHER", "UNKNOWN"].includes(token)) mapped.add("OTHER");
  }
  const labels = sportCategoryOptions
    .filter((opt) => mapped.has(opt.code))
    .map((opt) => opt.label);
  return labels.length === sportCategoryOptions.length ? [] : labels;
}
export function sportTypesSummary(sportTypes?: string[]): string {
  if (!sportTypes || !sportTypes.length) return "全部类型";
  const labels = sportTypeLabels(sportTypes);
  if (!labels.length) return "全部类型";
  return labels.length === 1 ? `仅${labels[0]}` : labels.join("、");
}
export const km = (meters?: number | null) =>
  meters == null
    ? "—"
    : (meters / 1000).toLocaleString("zh-CN", { maximumFractionDigits: 1 });
export function duration(seconds?: number | null) {
  if (seconds == null) return "—";
  const minutes = Math.floor(seconds / 60);
  return minutes >= 60
    ? `${Math.floor(minutes / 60)}小时${minutes % 60}分`
    : `${minutes}分钟`;
}
export function fileSize(bytes?: number | null) {
  if (!bytes || bytes <= 0) return "";
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
}
export function date(value?: number) {
  if (!value) return "尚无记录";
  return new Date(value).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}
export const statuses: Record<string, string> = {
  ACTIVE: "已连接",
  AUTH_EXPIRED: "登录已过期",
  CONNECTED: "已连接",
  QUEUED: "排队中",
  PENDING: "等待执行",
  RUNNING: "正在同步",
  CONFIRMING: "等待远端确认",
  SUCCESS: "已完成",
  SUCCEEDED: "已完成",
  COMPLETED: "已完成",
  FAILED: "失败",
  RETRY: "等待重试",
  RETRYABLE: "等待重试",
  WAITING_USER: "需要操作",
  CANCELLED: "已取消",
  PERMANENT_FAILURE: "无法完成",
  DISCONNECTED: "未连接",
};
export const statusLabel = (status: string) => statuses[status] || status;
export const jobKind = (kind: string) =>
  ({
    PULL: "拉取活动",
    DELIVERY: "投递活动",
    SYNC: "同步活动",
    IMPORT: "导入 FIT",
  })[kind] || kind;
