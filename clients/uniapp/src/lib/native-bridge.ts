import type { AdMobConfig } from "./types";

interface FitSyncNativeBridge {
  syncSession?: (token: string, apiBase: string) => void;
  clearSession?: () => void;
  syncAdConfig?: (configJson: string) => void;
  downloadFit?: (activityId: string, token: string, apiBase: string) => void;
  openExternalUrl?: (url: string) => void;
}

declare global {
  interface Window {
    FitSyncNative?: FitSyncNativeBridge;
  }
}

export function isFitSyncNativeApp(): boolean {
  return typeof window !== "undefined" && !!window.FitSyncNative;
}

export function nativeOAuthReturnUrl(path: string): string | null {
  if (!isFitSyncNativeApp()) return null;
  const safePath = path.startsWith("/") ? path : `/${path}`;
  return `fitsync-cloud://oauth?returnPath=${encodeURIComponent(safePath)}`;
}

export function notifyNativeSession(token: string, apiBase: string) {
  if (typeof window === "undefined") return;
  try {
    window.FitSyncNative?.syncSession?.(token, apiBase);
  } catch {
    // 非原生壳或旧版本桥接忽略错误。
  }
}

export function clearNativeSession() {
  if (typeof window === "undefined") return;
  try {
    window.FitSyncNative?.clearSession?.();
  } catch {
    // ignore
  }
}

export function notifyNativeAdConfig(admob?: AdMobConfig) {
  if (typeof window === "undefined") return;
  try {
    window.FitSyncNative?.syncAdConfig?.(
      JSON.stringify(
        admob ?? {
          enabled: false,
          bannerAdUnitId: "",
          rewardedAdUnitId: "",
        },
      ),
    );
  } catch {
    // ignore
  }
}

export function downloadFitViaNative(
  activityId: string,
  token: string,
  apiBase: string,
): boolean {
  if (typeof window === "undefined" || !window.FitSyncNative?.downloadFit) {
    return false;
  }
  window.FitSyncNative.downloadFit(activityId, token, apiBase);
  return true;
}
