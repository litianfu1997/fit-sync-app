import { reactive } from "vue";
import type { Config, LoginResult, User } from "./types";
import { themeColors } from "./theme";

const TOKEN_KEY = "fitsync.session.v1";
const BASE_KEY = "fitsync.server.v1";
export const session = reactive({
  token: String(uni.getStorageSync(TOKEN_KEY) || ""),
  user: null as User | null,
  config: null as Config | null,
  configError: "",
  connecting: false,
});
export function apiBase(): string {
  return String(
    uni.getStorageSync(BASE_KEY) || import.meta.env.VITE_API_BASE_URL || "/api",
  ).replace(/\/+$/, "");
}
export function validateBase(value: string): string {
  const base = value.trim().replace(/\/+$/, "");
  if (!base || (!/^https?:\/\/[^\s?#]+$/i.test(base) && base !== "/api"))
    throw new Error("请填写完整 API 地址，例如 https://你的域名/api");
  if (
    base.startsWith("http://") &&
    !/^http:\/\/(localhost|127\.0\.0\.1|\[::1\])(?::\d+)?(?:\/|$)/i.test(base)
  )
    throw new Error("平台授权需要 HTTPS；HTTP 仅允许连接本机开发服务");
  // #ifdef MP-WEIXIN
  if (!base.startsWith("https://"))
    throw new Error("微信小程序需要 HTTPS API 地址，并在微信后台配置合法域名");
  // #endif
  return base;
}
export function clearSession() {
  session.token = "";
  session.user = null;
  uni.removeStorageSync(TOKEN_KEY);
}
export function saveBase(value: string) {
  const base = validateBase(value);
  if (base !== apiBase()) {
    clearSession();
    session.config = null;
  }
  uni.setStorageSync(BASE_KEY, base);
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
      timeout: 30000,
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
      fail: () =>
        reject(
          new ApiError(
            "无法连接服务器。请检查服务器地址、网络或 HTTPS 合法域名配置。",
          ),
        ),
    });
  });
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
      if (session.token) session.user = await api<User>("/me");
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
/** 用户名密码登录，两端共用。 */
export async function loginWithPassword(username: string, password: string) {
  const result = await api<LoginResult>(
    "/auth/login",
    "POST",
    { username, password },
    false,
  );
  setSession(result);
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
/** 无密码账号补设用户名和密码。 */
export async function setPassword(
  username: string,
  password: string,
): Promise<User> {
  return api<User>("/auth/password", "POST", { username, password });
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
