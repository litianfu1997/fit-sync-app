import type { PlatformCode, WebSessionFields } from "./types";

export interface PlatformLoginHint {
  accountLabel: string;
  accountPlaceholder: string;
  note: string;
}

export const platformLoginHints: Partial<Record<PlatformCode, PlatformLoginHint>> = {
  COROS: {
    accountLabel: "COROS 账号",
    accountPlaceholder: "COROS 中国站邮箱或手机号",
    note: "仅支持 COROS 中国站。云端完成登录后加密保存会话令牌，可在令牌失效时自动续登。",
  },
  IGPSPORT: {
    accountLabel: "iGPSPORT 账号",
    accountPlaceholder: "iGPSPORT 中国站手机号、邮箱或用户名",
    note: "仅支持 iGPSPORT 中国站。云端完成登录后加密保存 JWT 会话，过期后可自动重新登录。",
  },
  ONELAP: {
    accountLabel: "迈金 / 顽鹿账号",
    accountPlaceholder: "顽鹿运动手机号或邮箱",
    note: "云端直接完成登录并保存 token 与 refresh_token，会话过期时先静默刷新，失败后再按授权自动重登。",
  },
  XINGZHE: {
    accountLabel: "行者账号",
    accountPlaceholder: "行者手机号、邮箱或用户名",
    note: "密码经 RSA 公钥加密提交至行者登录接口，云端自动提取并保存会话 Cookie。",
  },
  GARMIN: {
    accountLabel: "Garmin 中国站账号",
    accountPlaceholder: "Garmin 中国站（garmin.cn）邮箱或手机号",
    note: "仅支持 Garmin 中国站（garmin.cn）。云端自动换发 JWT_WEB、CSRF 与 SSO 长会话，优先通过 SSO 静默续期。",
  },
  STRAVA: {
    accountLabel: "Strava 官方授权",
    accountPlaceholder: "Strava 账号",
    note: "使用 Strava 官方 OAuth 2.0 跳转授权，自动保存并续期令牌；支持把本地或国内平台活动自动同步至 Strava（受开放 API 限制，暂不支持直接导出原始 FIT）。",
  },
};

/** 各平台的只读指引。这里不保存任何会话值，也不在网页中执行跨域抓取。 */
export interface WebSessionGuide {
  loginUrl: string;
  pageLabel: string;
  fields: ("cookie" | "token" | "refreshToken" | "csrf" | "ssoCookie" | "externalUserId")[];
  steps: string[];
  note: string;
}

export const webSessionGuides: Partial<Record<PlatformCode, WebSessionGuide>> = {
  ONELAP: {
    loginUrl: "https://otm.onelap.cn/recordPage",
    pageLabel: "迈金顽鹿运动网页",
    fields: ["token", "refreshToken", "cookie", "externalUserId"],
    steps: [
      "在浏览器打开顽鹿运动网页并登录，进入骑行记录页。",
      "打开开发者工具 → Application/存储 → Local Storage，复制 token、refresh_token 和 uid。",
      "在 Network/网络请求中复制当前请求的完整 Cookie。",
    ],
    note: "token 是迈金后台同步的主要凭据；refreshToken 用于过期后静默续期，Cookie 用于网页接口。",
  },
  XINGZHE: {
    loginUrl: "https://www.imxingzhe.com/login/",
    pageLabel: "行者网页",
    fields: ["cookie"],
    steps: [
      "在浏览器打开行者网页并完成登录。",
      "打开开发者工具 → Network/网络请求，选择 www.imxingzhe.com 的请求。",
      "复制请求头中的完整 Cookie。",
    ],
    note: "行者 Cookie 过期后需要在行者网页重新登录并导入新会话。",
  },
  GARMIN: {
    loginUrl: "https://connect.garmin.cn/signin/",
    pageLabel: "Garmin Connect 中国站",
    fields: ["cookie", "csrf", "ssoCookie"],
    steps: [
      "在浏览器打开 Garmin Connect 中国站并完成登录（仅限 garmin.cn）。",
      "在开发者工具 → Network 中复制 connect.garmin.cn 请求的完整 Cookie（必须含 JWT_WEB）。",
      "复制页面 <meta name=\"csrf-token\"> 的内容；如同时保留 sso.garmin.cn 的 Cookie，可让云端静默续期。",
    ],
    note: "中国站之外的 garmin.com 会话不被接受；页面验证码或二次认证仍需本人完成。",
  },
  STRAVA: {
    loginUrl: "https://www.strava.com/login",
    pageLabel: "Strava 网页",
    fields: ["cookie"],
    steps: [
      "在浏览器打开 Strava 网页并登录。",
      "打开开发者工具 → Application/存储 → Cookies，复制 www.strava.com 的 _strava4_session 等完整 Cookie。",
      "若使用邮箱密码登录失败，请先在浏览器完成 Google 登录或改用邮箱密码。",
    ],
    note: "不配置 Client ID／Secret；CSRF 由云服务在上传页自动获取，过期后重新导入 Cookie。",
  },
};

export function sessionFieldsFor(values: {
  cookie: string;
  token: string;
  refreshToken: string;
  csrf: string;
  ssoCookie: string;
  externalUserId: string;
}): WebSessionFields {
  const fields: WebSessionFields = {};
  if (values.cookie.trim()) fields.cookie = values.cookie.trim();
  if (values.token.trim()) fields.token = values.token.trim();
  if (values.refreshToken.trim()) fields.refreshToken = values.refreshToken.trim();
  if (values.csrf.trim()) fields.csrf = values.csrf.trim();
  if (values.ssoCookie.trim()) fields.ssoCookie = values.ssoCookie.trim();
  if (values.externalUserId.trim()) fields.externalUserId = values.externalUserId.trim();
  return fields;
}
