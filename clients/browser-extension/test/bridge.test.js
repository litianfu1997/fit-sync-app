/**
 * 后台登录窗口的离线回归测试。用内存模拟 chrome.windows/tabs/cookies 与 fetch，
 * 不访问任何真实平台，也不读取真实 Cookie。
 *
 * 运行：node clients/browser-extension/test/bridge.test.js
 */
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.resolve(__dirname, "../bridge.js"), "utf8");

function loadBridge({ cookies = [], submit, initialTabUrl = "" } = {}) {
  const listeners = { external: null, internal: null };
  const calls = { created: [], removed: [], submitted: [], permissionRequests: [] };
  const tabs = { updated: [], removed: [] };
  let cookieJar = cookies;
  let currentTabUrl = initialTabUrl;
  const context = {
    console,
    URL,
    fetch: async (url, options) => {
      calls.submitted.push({ url, body: JSON.parse(options.body) });
      return submit(url, options);
    },
    chrome: {
      runtime: {
        getManifest: () => ({ version: "0.1.0" }),
        onMessageExternal: {
          addListener: (fn) => {
            listeners.external = fn;
          },
        },
        onMessage: {
          addListener: (fn) => {
            listeners.internal = fn;
          },
        },
      },
      permissions: {
        contains: async ({ origins }) => {
          calls.permissionRequests.push(origins[0]);
          return true;
        },
        request: async () => true,
      },
      cookies: {
        getAll: async ({ url }) => {
          const host = new URL(url).hostname;
          return cookieJar.filter((cookie) => {
            const domain = (cookie.domain || "").replace(/^\./, "");
            return host === domain || host.endsWith("." + domain);
          });
        },
      },
      scripting: {
        executeScript: async () => [
          { result: { token: "onelap-token", refresh_token: "r1", uid: "u9" } },
        ],
      },
      windows: {
        create: async (options) => {
          calls.created.push(options);
          const tab = currentTabUrl ? { id: 11, pendingUrl: currentTabUrl } : { id: 11 };
          return { id: 77, tabs: [tab] };
        },
        remove: async (id) => {
          calls.removed.push(id);
        },
      },
      tabs: {
        get: async (id) => ({ id, url: currentTabUrl }),
        onUpdated: {
          addListener: (fn) => tabs.updated.push(fn),
          removeListener: (fn) => {
            tabs.updated = tabs.updated.filter((item) => item !== fn);
          },
        },
        onRemoved: {
          addListener: (fn) => tabs.removed.push(fn),
          removeListener: (fn) => {
            tabs.removed = tabs.removed.filter((item) => item !== fn);
          },
        },
      },
    },
    // 登录等待使用 10 分钟真实定时器；测试里替换为可清理的短定时器，避免进程悬挂。
    setTimeout: (fn, ms) => setTimeout(fn, Math.min(ms, 2000)),
    clearTimeout,
    setInterval: (fn, ms) => setInterval(fn, Math.min(ms, 50)),
    clearInterval,
  };
  vm.createContext(context);
  vm.runInContext(source, context);
  return {
    calls,
    tabs,
    setCookies(next) {
      cookieJar = next;
    },
    setTabUrl(url) {
      currentTabUrl = url;
    },
    send(message, internal = false) {
      return Promise.race([
        new Promise((resolve) => {
          const handler = internal ? listeners.internal : listeners.external;
          const async = handler(message, {}, resolve);
          if (!async) resolve(undefined);
        }),
        new Promise((_, reject) =>
          setTimeout(() => reject(new Error("扩展响应超时：" + message.type)), 5000),
        ),
      ]);
    },
  };
}

function okSubmit(displayName) {
  return async () => ({
    ok: true,
    status: 200,
    text: async () => JSON.stringify({ id: "a1", displayName }),
  });
}

function fireComplete(bridge, tabId = 11, extra = {}) {
  if (extra.url && bridge.setTabUrl) bridge.setTabUrl(extra.url);
  for (const listener of bridge.tabs.updated) listener(tabId, { status: "complete", ...extra });
}

async function connectWithCookies(message, cookies, displayName = "我的账号") {
  // 先放入“登录前”的 Cookie，窗口打开后才能模拟用户登录完成。
  const bridge = loadBridge({ cookies: [], submit: okSubmit(displayName) });
  const pending = bridge.send(message);
  await new Promise((resolve) => setTimeout(resolve, 0));
  bridge.setCookies(cookies);
  fireComplete(bridge);
  return { bridge, result: await pending };
}

const strava = {
  type: "fitsync.connect",
  platform: "STRAVA",
  service: "http://127.0.0.1:8080/api",
  token: "secret-token",
  url: "https://www.strava.com/login",
};
const garmin = {
  ...strava,
  platform: "GARMIN",
  url: "https://connect.garmin.cn/signin/",
};
const cookie = (name, value, domain = "strava.com") => ({ name, value, domain });

(async () => {
  // 1) ping 只报告扩展存在，不读取任何 Cookie
  const bridge = loadBridge();
  const pong = await bridge.send({ type: "fitsync.ping" });
  assert.equal(pong.installed, true);
  assert.equal(pong.version, "0.1.0");
  assert.equal(bridge.calls.created.length, 0);

  // 2) 未知平台 / 地址不匹配被拒绝，不打开窗口
  const rejected = await bridge.send({ ...strava, platform: "EVIL" });
  assert.equal(rejected.ok, false);
  assert.match(rejected.error, /不支持/);
  const mismatch = await bridge.send({ ...strava, url: "https://evil.example/login" });
  assert.match(mismatch.error, /地址不匹配/);
  assert.equal(bridge.calls.created.length, 0);

  // 3) 正常 Strava 流程：弹出登录窗口 → 登录 → 提交 → 关闭窗口
  const stravaRun = await connectWithCookies(strava, [
    cookie("_strava4_session", "secret"),
    cookie("tracker", "junk"),
  ]);
  assert.equal(stravaRun.result.ok, true, JSON.stringify(stravaRun.result));
  assert.equal(stravaRun.result.displayName, "我的账号");
  assert.equal(stravaRun.bridge.calls.created[0].type, "popup");
  assert.equal(stravaRun.bridge.calls.created[0].url, "https://www.strava.com/login");
  assert.deepEqual(stravaRun.bridge.calls.removed, [77]);
  const submitted = stravaRun.bridge.calls.submitted[0];
  assert.equal(submitted.url, "http://127.0.0.1:8080/api/v1/accounts/web-session");
  assert.equal(submitted.body.platform, "STRAVA");
  assert.match(submitted.body.session.cookie, /_strava4_session=secret/);

  // 3A) Strava 登录页的匿名 _strava4_session 不会提前关窗，跳转到仪表盘后才提交
  const stravaOtp = loadBridge({
    cookies: [cookie("_strava4_session", "anon")],
    submit: okSubmit("验证码账号"),
    initialTabUrl: "https://www.strava.com/login",
  });
  const { url: _ignoredUrl, ...stravaWithoutUrl } = strava;
  const otpPending = stravaOtp.send(stravaWithoutUrl);
  await new Promise((resolve) => setTimeout(resolve, 0));
  fireComplete(stravaOtp, 11, { url: "https://www.strava.com/login" });
  await new Promise((resolve) => setTimeout(resolve, 80));
  assert.equal(stravaOtp.calls.submitted.length, 0);
  assert.equal(stravaOtp.calls.removed.length, 0);
  stravaOtp.setCookies([cookie("_strava4_session", "authed")]);
  fireComplete(stravaOtp, 11, { url: "https://www.strava.com/dashboard" });
  const otpResult = await otpPending;
  assert.equal(otpResult.ok, true);
  assert.equal(otpResult.displayName, "验证码账号");
  assert.match(stravaOtp.calls.submitted[0].body.session.cookie, /_strava4_session=authed/);
  assert.deepEqual(stravaOtp.calls.removed, [77]);

  // 4A) Garmin 允许官方 /signin/ 入口，且换域仍被拒绝
  const garminRun = await connectWithCookies(
    garmin,
    [cookie("JWT_WEB", "garmintoken", "connect.garmin.cn")],
    "我的 Garmin",
  );
  assert.equal(garminRun.result.ok, true, JSON.stringify(garminRun.result));
  assert.equal(
    garminRun.bridge.calls.created[0].url,
    "https://connect.garmin.cn/modern/",
  );
  const badGarmin = await loadBridge({ submit: okSubmit("x") }).send({
    ...garmin,
    url: "https://connect.garmin.cn.evil.example/signin/",
  });
  assert.match(badGarmin.error, /地址不匹配/);

  // 4) 登录未完成时窗口关闭：明确报错，不提交会话
  const closed = loadBridge({ cookies: [], submit: okSubmit("x") });
  const closedPending = closed.send(strava);
  await new Promise((resolve) => setTimeout(resolve, 0));
  for (const listener of closed.tabs.removed) listener(77);
  const closedResult = await closedPending;
  assert.equal(closedResult.ok, false);
  assert.match(closedResult.error, /未检测到有效登录会话/);
  assert.equal(closed.calls.submitted.length, 0);

  // 5) 迈金额外读取 localStorage token，不把缺 token 当成成功
  const onelap = {
    ...strava,
    platform: "ONELAP",
    url: "https://otm.onelap.cn/recordPage",
  };
  const onelapRun = await connectWithCookies(
    onelap,
    [cookie("SESSION", "abc", "otm.onelap.cn"), cookie("token", "txtoken", "otm.onelap.cn")],
    "我的迈金",
  );
  assert.equal(onelapRun.result.ok, true);
  const onelapBody = onelapRun.bridge.calls.submitted[0].body.session;
  assert.equal(onelapBody.token, "onelap-token");
  assert.equal(onelapBody.refreshToken, "r1");
  assert.equal(onelapBody.externalUserId, "u9");

  // 6) 服务端拒绝时原样回传中文原因
  const failing = loadBridge({
    cookies: [cookie("_strava4_session", "expired")],
    submit: async () => ({
      ok: false,
      status: 400,
      text: async () => JSON.stringify({ message: "Strava 网页登录已失效，请重新登录" }),
    }),
  });
  const failingPending = failing.send(strava);
  await new Promise((resolve) => setTimeout(resolve, 0));
  failing.setCookies([cookie("_strava4_session", "expired")]);
  fireComplete(failing);
  const failed = await failingPending;
  assert.equal(failed.ok, false);
  assert.match(failed.error, /登录已失效/);

  // 7) 弹窗内部通道也能发起同一套流程（不能绕过平台地址校验）
  const internal = loadBridge({ cookies: [], submit: okSubmit("弹窗账号") });
  const internalPending = internal.send(
    { ...strava, url: "https://www.strava.com/login" },
    true,
  );
  await new Promise((resolve) => setTimeout(resolve, 20));
  internal.setCookies([cookie("_strava4_session", "from-popup")]);
  fireComplete(internal);
  const internalResult = await internalPending;
  assert.equal(internalResult.ok, true);
  assert.equal(internalResult.displayName, "弹窗账号");

  console.log("browser-extension bridge: all checks passed");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
