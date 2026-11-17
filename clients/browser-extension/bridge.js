/**
 * FitSync 会话助手后台：负责打开受控登录小窗、等待用户完成人工登录，
 * 然后读取 Cookie 并提交到用户指定的 FitSync 云服务。
 *
 * 不注入远程代码、不采集浏览历史、不保存平台 Cookie。登录窗口关闭或超时即停止。
 */
const PLATFORMS = {
  STRAVA: {
    label: "Strava",
    loginUrls: ["https://www.strava.com/login"],
    cookies: ["https://www.strava.com/"],
    required: /(^|;\s*)_strava4_session=/,
    loggedOutPath: /^\/(login|register|session|account\/recover)(\/|$)/i,
  },
  GARMIN: {
    label: "Garmin Connect 中国站",
    // 中国站可能落在 Connect 主站或单点登录页；两者都必须是 garmin.cn 域名。
    loginUrls: [
      "https://connect.garmin.cn/modern/",
      "https://connect.garmin.cn/signin/",
      "https://sso.garmin.cn/portal/sso/zh-CN/sign-in",
    ],
    cookies: ["https://connect.garmin.cn/", "https://sso.garmin.cn/"],
    required: /(^|;\s*)JWT_WEB=/,
  },
  ONELAP: {
    label: "迈金 ONELAP",
    loginUrls: ["https://otm.onelap.cn/recordPage"],
    cookies: ["https://otm.onelap.cn/"],
    required: /(^|;\s*)token=/,
    localStorage: ["token", "refresh_token", "uid"],
  },
  XINGZHE: {
    label: "行者",
    loginUrls: ["https://www.imxingzhe.com/login/", "https://www.imxingzhe.com/"],
    cookies: ["https://www.imxingzhe.com/"],
    required: /(^|;\s*)\S*session\S*=/i,
  },
};

const SERVICE_KEY = "fitsync.service";
const TOKEN_KEY = "fitsync.token";
const LOGIN_TIMEOUT_MS = 10 * 60 * 1000;

/** 仅允许已登记平台及其官方登录页，避免被任意网页当成通用 Cookie 读取器。 */
function platformOf(message) {
  const platform = PLATFORMS[message?.platform];
  if (!platform) throw new Error("不支持的运动平台");
  const targetUrl = message?.url || platform.loginUrls[0];
  const allowed = platform.loginUrls.some((base) => {
    if (targetUrl === base) return true;
    // 允许同一官方路径下的查询参数（如 service/callback），但不允许换域或跳路径。
    return targetUrl?.startsWith(base.endsWith("/") ? base : base + "?");
  });
  if (!allowed) throw new Error("平台登录地址不匹配");
  return platform;
}

function serviceBase(raw) {
  const value = String(raw || "").trim().replace(/\/+$/, "");
  if (!value) throw new Error("请填写 FitSync 云服务地址");
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error("服务地址格式无效，例如 https://sync.example.com/api");
  }
  const local = url.protocol === "http:" && ["127.0.0.1", "localhost"].includes(url.hostname);
  if (url.protocol !== "https:" && !local)
    throw new Error("云端地址必须使用 HTTPS；HTTP 仅允许本机开发服务");
  if (!url.pathname.endsWith("/api"))
    throw new Error("服务地址需以 /api 结尾，例如 https://example.com/api");
  return value;
}

async function cookieHeader(urls) {
  const values = new Map();
  for (const url of urls) {
    const cookies = await chrome.cookies.getAll({ url });
    for (const cookie of cookies) {
      if (cookie.value) values.set(cookie.name, cookie.value);
    }
  }
  return [...values.entries()].map(([name, value]) => `${name}=${value}`).join("; ");
}

async function readLocalStorage(tabId, keys) {
  if (!tabId) return {};
  try {
    const [execution] = await chrome.scripting.executeScript({
      target: { tabId },
      func: (names) => {
        const result = {};
        for (const name of names) {
          try {
            const value = window.localStorage.getItem(name);
            if (value) result[name] = value;
          } catch {
            // 页面拒绝访问时忽略，由调用方按缺失字段提示。
          }
        }
        return result;
      },
      args: [keys],
    });
    return execution?.result || {};
  } catch {
    return {};
  }
}

function waitForLogin(windowId, tabId, platform, initialUrl = "") {
  return new Promise((resolve, reject) => {
    let finished = false;
    let sawLogin = false;
    let lastTabUrl = initialUrl;
    const finish = (error) => {
      if (finished) return;
      finished = true;
      clearInterval(poll);
      clearTimeout(timer);
      chrome.tabs.onUpdated.removeListener(onUpdated);
      chrome.tabs.onRemoved.removeListener(onRemoved);
      if (error) reject(error);
      else resolve();
    };
    const isStillLoggedOut = async (cookie) => {
      if (!platform.loggedOutPath) return false;
      if (typeof chrome.tabs?.get === "function" && tabId != null) {
        try {
          const tab = await chrome.tabs.get(tabId);
          if (tab?.url || tab?.pendingUrl) lastTabUrl = tab.url || tab.pendingUrl;
        } catch {
          // 窗口导航期间读取标签页失败时沿用最近一次记录的地址。
        }
      }
      if (!lastTabUrl) return false;
      if (lastTabUrl === "about:blank") return true;
      try {
        const pathname = new URL(lastTabUrl).pathname;
        if (!platform.loggedOutPath.test(pathname)) return false;
        // Strava 打开登录页即会设置匿名 _strava4_session；仅在离开登录页或获得记住登录令牌后才视为登录完成。
        return !(/(^|;\s*)strava_remember_token=/.test(cookie));
      } catch {
        return false;
      }
    };
    const check = async () => {
      if (finished) return;
      try {
        const cookie = await cookieHeader(platform.cookies);
        if (!platform.required.test(cookie)) return;
        if (await isStillLoggedOut(cookie)) return;
        sawLogin = true;
        finish();
      } catch {
        // 窗口导航期间读取失败可忽略，下一轮再试。
      }
    };
    const onUpdated = (updatedTabId, info, tab) => {
      if (updatedTabId !== tabId) return;
      if (info?.url || tab?.url || tab?.pendingUrl) {
        lastTabUrl = info.url || tab?.url || tab?.pendingUrl;
      }
      if (info.status !== "complete") return;
      void check();
    };
    const onRemoved = (removedId) => {
      if (removedId !== windowId) return;
      if (sawLogin) finish();
      else finish(new Error("登录窗口已关闭，未检测到有效登录会话"));
    };
    const timer = setTimeout(
      () => finish(new Error("等待登录超时，请重新发起授权")),
      LOGIN_TIMEOUT_MS,
    );
    // 单页登录可能不触发 complete（SPA 路由），同时低频轮询会话是否已建立。
    const poll = setInterval(check, 2000);
    chrome.tabs.onUpdated.addListener(onUpdated);
    chrome.tabs.onRemoved.addListener(onRemoved);
    void check();
  });
}

async function startLogin(message) {
  const platform = platformOf(message);
  const base = serviceBase(message.service);
  const token = String(message.token || "").trim();
  if (!token) throw new Error("缺少 FitSync 登录令牌，请先登录网页");
  const origin = new URL(base).origin + "/*";
  const permitted = await chrome.permissions.contains({ origins: [origin] });
  if (!permitted && !(await chrome.permissions.request({ origins: [origin] })))
    throw new Error("需要授权访问该 FitSync 服务域名才能提交会话");

  const win = await chrome.windows.create({
    url: platform.loginUrls[0],
    type: "popup",
    width: 520,
    height: 760,
    focused: true,
  });
  const tab = win?.tabs?.[0];
  const tabId = tab?.id;
  await waitForLogin(win.id, tabId, platform, tab?.pendingUrl || tab?.url || "");

  const cookie = await cookieHeader(platform.cookies);
  if (!platform.required.test(cookie)) throw new Error(`${platform.label} 未返回有效登录会话`);
  const session = { cookie };
  if (platform.localStorage) {
    const local = await readLocalStorage(tabId, platform.localStorage);
    if (!local.token) throw new Error("未读取到迈金 token，请在登录窗口打开骑行记录页后重试");
    session.token = local.token;
    if (local.refresh_token) session.refreshToken = local.refresh_token;
    if (local.uid) session.externalUserId = local.uid;
  }
  try {
    await chrome.windows.remove(win.id);
  } catch {
    // 用户可能已手动关闭窗口。
  }
  const response = await fetch(`${base}/v1/accounts/web-session`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ platform: message.platform, session }),
  });
  const text = await response.text();
  let data = {};
  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    data = {};
  }
  if (!response.ok) throw new Error(data.message || `FitSync 返回 HTTP ${response.status}`);
  return {
    ok: true,
    platform: message.platform,
    displayName: data.displayName || platform.label,
  };
}

chrome.runtime.onMessageExternal.addListener((message, sender, sendResponse) => {
  if (message?.type === "fitsync.ping") {
    sendResponse({ installed: true, version: chrome.runtime.getManifest().version });
    return;
  }
  if (message?.type === "fitsync.connect") {
    // 只接受外部网页发起的连接；弹窗自身用 runtime.sendMessage 走内部通道。
    startLogin(message)
      .then(sendResponse)
      .catch((error) => {
        sendResponse({ ok: false, error: error?.message || "连接失败，请重试" });
      });
    return true;
  }
  sendResponse({ installed: false, error: "未知请求" });
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type !== "fitsync.connect") return;
  startLogin(message)
    .then(sendResponse)
    .catch((error) => {
      sendResponse({ ok: false, error: error?.message || "连接失败，请重试" });
    });
  return true;
});
