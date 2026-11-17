import { apiBase, session } from "./api";
import type { PlatformCode } from "./types";
import { webSessionGuides } from "./web-session";

/**
 * 浏览器扩展桥接（仅 H5）。
 *
 * 浏览器安全模型禁止网页读取其他站点的 Cookie；扩展在用户点击后打开受控登录窗口，
 * 等待人工完成登录，再读取 Cookie 并提交到当前 FitSync 服务。
 */
const ENV_EXTENSION_ID =
  (import.meta.env.VITE_FITSYNC_EXTENSION_ID as string | undefined) || "";
const EXTENSION_ID_KEY = "fitsync.extensionId";

export type ExtensionState = "unknown" | "installed" | "missing";

export function getExtensionId(): string {
  const stored = String(uni.getStorageSync(EXTENSION_ID_KEY) || "").trim();
  return (ENV_EXTENSION_ID || stored).trim();
}

export function setExtensionId(id: string): string {
  const cleaned = id.trim();
  if (cleaned) uni.setStorageSync(EXTENSION_ID_KEY, cleaned);
  else uni.removeStorageSync(EXTENSION_ID_KEY);
  return getExtensionId();
}

function absoluteService(): string {
  const base = apiBase();
  if (/^https?:\/\//i.test(base)) return base;
  const origin = typeof window !== "undefined" ? window.location.origin : "";
  return origin ? new URL(base, origin).toString().replace(/\/+$/, "") : base;
}

export function extensionAvailable(): boolean {
  return !!getExtensionId() && typeof (globalThis as any).chrome?.runtime?.sendMessage === "function";
}

/** 通过 externally_connectable 的 ping 判断扩展是否安装；失败即视为未安装。 */
export function detectExtension(timeoutMs = 800): Promise<ExtensionState> {
  return new Promise((resolve) => {
    const runtime = (globalThis as any).chrome?.runtime;
    const extensionId = getExtensionId();
    if (!extensionId || typeof runtime?.sendMessage !== "function") {
      resolve("unknown");
      return;
    }
    let settled = false;
    const finish = (state: ExtensionState) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(state);
    };
    const timer = setTimeout(() => finish("missing"), timeoutMs);
    try {
      runtime.sendMessage(
        extensionId,
        { type: "fitsync.ping" },
        (response: any) => {
          if (runtime.lastError) {
            finish("missing");
            return;
          }
          finish(response?.installed ? "installed" : "missing");
        },
      );
    } catch {
      finish("missing");
    }
  });
}

export interface ExtensionConnectResult {
  ok: boolean;
  displayName?: string;
  error?: string;
}

/**
 * 请求扩展弹出人工登录小窗。扩展在登录完成后读取会话、提交到当前服务，
 * 并把结果回传；用户中途关闭窗口会得到明确错误，不会留下半连接账号。
 */
export function connectViaExtension(platform: string): Promise<ExtensionConnectResult> {
  return new Promise((resolve) => {
    const runtime = (globalThis as any).chrome?.runtime;
    const extensionId = getExtensionId();
    if (!extensionId || typeof runtime?.sendMessage !== "function" || !session.token) {
      resolve({ ok: false, error: "未检测到浏览器扩展或当前未登录" });
      return;
    }
    const loginUrl = webSessionGuides[platform as PlatformCode]?.loginUrl;
    // 与服务端「仅用户点击时连接」一致：10 分钟内未完成登录则返回超时，
    // 不把未完成的登录窗口当作长期后台任务。
    let settled = false;
    const finish = (result: ExtensionConnectResult) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(result);
    };
    const timer = setTimeout(
      () => finish({ ok: false, error: "等待登录超时，请重新发起连接" }),
      10 * 60 * 1000,
    );
    try {
      runtime.sendMessage(
        extensionId,
        {
          type: "fitsync.connect",
          platform,
          service: absoluteService(),
          token: session.token,
          ...(loginUrl ? { url: loginUrl } : {}),
        },
        (response: any) => {
          if (runtime.lastError) {
            finish({ ok: false, error: "扩展通信失败，请刷新页面后重试" });
            return;
          }
          finish(response || { ok: false, error: "扩展没有返回结果" });
        },
      );
    } catch {
      finish({ ok: false, error: "扩展通信失败，请刷新页面后重试" });
    }
  });
}

