import { reactive } from "vue";
import type { Config, LoginResult, User } from "./types";
import { themeColors } from "./theme";

const TOKEN_KEY = "fitsync.session.v1";
export const session = reactive({
  token: String(uni.getStorageSync(TOKEN_KEY) || ""),
  user: null as User | null,
  config: null as Config | null,
  configError: "",
  authNotice: "",
  googleBindSuccess: false,
  connecting: false,
});
const LOCAL_HTTP_URL =
  /^http:\/\/(localhost|127\.0\.0\.1|\[::1\])(?::\d+)?(?:\/|$)/i;

export function apiBase(): string {
  let fallback = "/api";
  // #ifdef MP-WEIXIN
  if (import.meta.env.DEV) fallback = "http://127.0.0.1:8080/api";
  // #endif
  return String(import.meta.env.VITE_API_BASE_URL || fallback).replace(
    /\/+$/,
    "",
  );
}
export function validateBase(value: string): string {
  const base = value.trim().replace(/\/+$/, "");
  if (!base || (!/^https?:\/\/[^\s?#]+$/i.test(base) && base !== "/api"))
    throw new Error("API 地址配置无效，请检查构建配置");
  if (base.startsWith("http://") && !LOCAL_HTTP_URL.test(base))
    throw new Error("平台授权需要 HTTPS；HTTP 仅允许连接本机开发服务");
  // #ifdef MP-WEIXIN
  const allowLocalHttp = import.meta.env.DEV && LOCAL_HTTP_URL.test(base);
  if (!base.startsWith("https://") && !allowLocalHttp)
    throw new Error("微信小程序需要 HTTPS API 地址，并在微信后台配置合法域名");
  // #endif
  return base;
}
export function clearSession() {
  session.token = "";
  session.user = null;
  uni.removeStorageSync(TOKEN_KEY);
}
export class ApiError extends Error {
  constructor(
    message: string,
    public status = 0,
    public code = "",
  ) {
    super(message);
  }
}
function errorResponse(status: number, data: any): ApiError {
  if (status === 401) clearSession();
  return new ApiError(
    data?.message || `请求未完成（${status}）`,
    status,
    data?.code || "",
  );
}
export function api<T>(
  path: string,
  method: "GET" | "POST" | "PUT" | "DELETE" = "GET",
  data?: any,
  authenticated = true,
  extraHeaders: Record<string, string> = {},
  timeoutMs = 30000,
): Promise<T> {
  let base: string;
  try {
    base = validateBase(apiBase());
  } catch (error) {
    return Promise.reject(error);
  }
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${base}/v1${path}`,
      method,
      data,
      timeout: timeoutMs,
      header: {
        "Content-Type": "application/json",
        ...(authenticated && session.token
          ? { Authorization: `Bearer ${session.token}` }
          : {}),
        ...extraHeaders,
      },
      success: (res) => {
        if (res.statusCode >= 200 && res.statusCode < 300)
          resolve(res.data as T);
        else reject(errorResponse(res.statusCode, res.data));
      },
      fail: (err) => {
        const timedOut = /timeout/i.test(String(err?.errMsg || ""));
        reject(
          new ApiError(
            timedOut
              ? "平台响应超时，请稍后重试；若已收到验证码邮件，请重新点击获取验证码后再提交。"
              : "无法连接云服务，请检查网络后重试。",
          ),
        );
      },
    });
  });
}
function extractH5GoogleParams(): {
  ticket: string;
  oauth: string;
  oauthMsg: string;
} | null {
  if (typeof window === "undefined") return null;
  const searchParams = new URLSearchParams(window.location.search);
  const hash = window.location.hash || "";
  const hashQ = hash.indexOf("?");
  const hashPath = hashQ >= 0 ? hash.slice(0, hashQ) : hash;
  const hashParams = new URLSearchParams(hashQ >= 0 ? hash.slice(hashQ + 1) : "");

  const ticket =
    hashParams.get("googleTicket") || searchParams.get("googleTicket") || "";
  const oauth =
    hashParams.get("googleOAuth") || searchParams.get("googleOAuth") || "";
  const oauthMsg =
    hashParams.get("googleOAuthMsg") || searchParams.get("googleOAuthMsg") || "";

  if (!ticket && !oauth && !oauthMsg) return null;

  for (const key of ["googleTicket", "googleOAuth", "googleOAuthMsg"]) {
    searchParams.delete(key);
    hashParams.delete(key);
  }
  const nextSearch = searchParams.toString();
  const nextHashQuery = hashParams.toString();
  const nextHash = hashPath + (nextHashQuery ? `?${nextHashQuery}` : "");
  const nextUrl =
    window.location.pathname +
    (nextSearch ? `?${nextSearch}` : "") +
    nextHash;
  window.history.replaceState(null, "", nextUrl);
  return { ticket, oauth, oauthMsg };
}

let initializing: Promise<void> | null = null;
export async function initialize(force = false): Promise<void> {
  if (initializing) return initializing;
  if (session.config && !force) return;
  initializing = (async () => {
    session.connecting = true;
    session.configError = "";
    try {
      session.config = await api<Config>("/config", "GET", undefined, false);
      let exchanged = false;
      // #ifdef H5
      const callback = extractH5GoogleParams();
      if (callback?.ticket) {
        try {
          await exchangeGoogleTicket(callback.ticket);
          exchanged = true;
        } catch (err) {
          session.authNotice = messageOf(err);
        }
      } else if (callback?.oauth === "success") {
        session.googleBindSuccess = true;
      } else if (callback?.oauth === "error") {
        session.authNotice =
          callback.oauthMsg || "Google 授权未完成，请重试";
      }
      // #endif
      if (session.token && !exchanged) session.user = await api<User>("/me");
    } catch (error) {
      session.configError =
        error instanceof ApiError && error.status === 401
          ? ""
          : messageOf(error);
    } finally {
      session.connecting = false;
      initializing = null;
    }
  })();
  return initializing;
}
/** 发送邮箱验证码（注册或绑定邮箱）。 */
export async function sendEmailCode(
  email: string,
  purpose: "REGISTER" | "BIND",
): Promise<{ success: boolean }> {
  return api<{ success: boolean }>(
    "/auth/email/code",
    "POST",
    { email, purpose },
    purpose === "BIND",
  );
}
/** 邮箱验证码注册，成功后保存会话。 */
export async function registerWithEmail(
  email: string,
  code: string,
  password: string,
  displayName: string,
) {
  const result = await api<LoginResult>(
    "/auth/register",
    "POST",
    { email, code, password, displayName },
    false,
  );
  setSession(result);
}
/** 用户名密码注册，成功后保存会话。 */
export async function register(
  username: string,
  password: string,
  displayName: string,
) {
  const result = await api<LoginResult>(
    "/auth/register",
    "POST",
    { username, password, displayName },
    false,
  );
  setSession(result);
}
/** 邮箱或用户名密码登录，两端共用。 */
export async function loginWithPassword(account: string, password: string) {
  const result = await api<LoginResult>(
    "/auth/login",
    "POST",
    { account, password },
    false,
  );
  setSession(result);
}
/** 已登录用户校验邮箱验证码并绑定邮箱。 */
export async function bindEmail(email: string, code: string): Promise<User> {
  return api<User>("/auth/email/bind", "POST", { email, code });
}
/** 使用一次性票据交换 Google 登录会话。 */
export async function exchangeGoogleTicket(ticket: string) {
  const result = await api<LoginResult>(
    "/auth/google/exchange",
    "POST",
    { ticket },
    false,
  );
  setSession(result);
}
/** 发起网页端 Google OAuth 2.0 登录或绑定跳转。 */
export async function startGoogleOAuth(
  mode: "login" | "bind",
  returnPath?: string,
) {
  if (typeof window === "undefined") {
    throw new Error("当前环境不支持网页 Google 授权");
  }
  const base = apiBase();
  const absApi = /^https?:\/\//i.test(base)
    ? base
    : new URL(base, window.location.origin).toString().replace(/\/+$/, "");
  const redirectUri = `${absApi}/v1/auth/google/callback`;
  const currentHash = window.location.hash.split("?")[0];
  const resolvedPath =
    returnPath ||
    (currentHash.startsWith("#/")
      ? currentHash.slice(1)
      : "/pages/overview/index");
  const returnUrl = `${window.location.origin}/#${resolvedPath}`;
  const endpoint =
    mode === "bind" ? "/auth/google/bind/start" : "/auth/google/start";
  const result = await api<{ challengeId: string; authorizeUrl: string }>(
    endpoint,
    "POST",
    { redirectUri, returnUrl },
    mode === "bind",
  );
  window.location.assign(result.authorizeUrl);
}
/** 小程序微信一键登录；仅微信端调用。 */
export async function loginWithWechat() {
  const code = await wechatCode();
  const result = await api<LoginResult>("/auth/wechat", "POST", { code }, false);
  setSession(result);
}
/** 本地开发登录，每次新建独立用户。 */
export async function loginAsDev(displayName: string) {
  const result = await api<LoginResult>(
    "/auth/dev-login",
    "POST",
    { displayName: displayName.trim() || "本地体验用户" },
    false,
  );
  setSession(result);
}
export function wechatCode(): Promise<string> {
  return new Promise<string>((resolve, reject) =>
    uni.login({
      provider: "weixin",
      success: (r) =>
        r.code
          ? resolve(r.code)
          : reject(new Error("未获取到微信登录凭证，请重试")),
      fail: () => reject(new Error("微信登录未完成，请重试")),
    }),
  );
}
/** 把当前账号绑定微信，使小程序和网页落到同一业务用户。 */
export async function bindWechat(): Promise<User> {
  const code = await wechatCode();
  return api<User>("/auth/wechat/bind", "POST", { code });
}
/** 无密码账号补设密码（已有绑定邮箱时用户名可选填）。 */
export async function setPassword(
  username: string,
  password: string,
): Promise<User> {
  const trimmed = username.trim();
  return api<User>(
    "/auth/password",
    "POST",
    trimmed ? { username: trimmed, password } : { password },
  );
}
/** 已有密码时凭原密码修改。 */
export async function changePassword(
  oldPassword: string,
  newPassword: string,
): Promise<{ success: boolean }> {
  return api("/auth/password/change", "POST", { oldPassword, newPassword });
}
/** 修改展示昵称。 */
export async function updateProfile(displayName: string): Promise<User> {
  return api<User>("/me", "PUT", { displayName });
}
export function setSession(result: LoginResult) {
  session.token = result.token;
  session.user = result.user;
  uni.setStorageSync(TOKEN_KEY, result.token);
}
/** 本地更新用户资料后同步内存中的会话对象。 */
export function patchSessionUser(user: User) {
  session.user = user;
}
export function uploadFit(
  filePath: string,
): Promise<{ activityId: string; duplicate: boolean }> {
  return new Promise((resolve, reject) =>
    uni.uploadFile({
      url: `${validateBase(apiBase())}/v1/activities/import`,
      filePath,
      name: "file",
      timeout: 120000,
      header: { Authorization: `Bearer ${session.token}` },
      success: (r) => {
        let data: any;
        try {
          data = JSON.parse(r.data);
        } catch {
          reject(new Error("服务器返回了无法识别的导入结果"));
          return;
        }
        if (r.statusCode >= 200 && r.statusCode < 300) resolve(data);
        else reject(errorResponse(r.statusCode, data));
      },
      fail: () => reject(new Error("FIT 上传失败，请检查网络后重试")),
    }),
  );
}
export function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : "操作未完成，请重试";
}
export function toast(text: string) {
  uni.showToast({ title: text, icon: "none", duration: 2600 });
}
export function confirm(title: string, content: string): Promise<boolean> {
  return new Promise((resolve) =>
    uni.showModal({
      title,
      content,
      confirmColor: themeColors.value.primary,
      success: (result) => resolve(result.confirm),
    }),
  );
}
export function go(page: string) {
  uni.reLaunch({ url: `/pages/${page}/index` });
}
export function detail(id: string) {
  uni.navigateTo({ url: `/pages/activity/index?id=${encodeURIComponent(id)}` });
}
export function openMap(id: string) {
  uni.navigateTo({ url: `/pages/map/index?id=${encodeURIComponent(id)}` });
}
