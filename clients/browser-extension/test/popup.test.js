/**
 * 扩展弹窗的离线测试。弹窗只负责保存服务地址/令牌，并把连接请求交给后台；
 * 不读取 Cookie，也不访问真实平台。
 *
 * 运行：node clients/browser-extension/test/popup.test.js
 */
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const origin = path.resolve(__dirname, "..");

function element(value = "") {
  return {
    value,
    textContent: "",
    className: "",
    innerHTML: "",
    href: "",
    listeners: {},
    addEventListener(name, handler) {
      this.listeners[name] = handler;
    },
  };
}

function loadPopup({ stored = {}, backgroundResult = { ok: true, displayName: "我的 Strava" } } = {}) {
  const elements = {
    service: element(stored["fitsync.service"] || ""),
    token: element(stored["fitsync.token"] ? "••••••••（已保存）" : ""),
    platform: element("STRAVA"),
    "login-link": element(),
    status: element(),
    connect: element(),
    save: element(),
    logout: element(),
  };
  const calls = { sent: [], permissionRequests: [], createdTabs: [] };
  const context = {
    console,
    URL,
    document: {
      getElementById: (id) => {
        if (!elements[id]) throw new Error("missing element " + id);
        return elements[id];
      },
    },
    chrome: {
      storage: {
        local: {
          get: async (keys) => {
            const result = {};
            for (const key of keys) if (key in stored) result[key] = stored[key];
            return result;
          },
          set: async (values) => Object.assign(stored, values),
          remove: async (keys) => {
            for (const key of keys) delete stored[key];
          },
        },
      },
      permissions: {
        contains: async () => false,
        request: async ({ origins }) => {
          calls.permissionRequests.push(...origins);
          return true;
        },
      },
      tabs: {
        query: async () => [],
        create: async (options) => calls.createdTabs.push(options.url),
      },
      runtime: {
        getManifest: () => ({ version: "0.1.0" }),
        onMessageExternal: { addListener: () => {} },
        sendMessage: async (message) => {
          calls.sent.push(message);
          return backgroundResult;
        },
      },
    },
    setTimeout,
    clearTimeout,
  };
  const code = fs.readFileSync(path.join(origin, "popup.js"), "utf8");
  vm.createContext(context);
  vm.runInContext(code, context);
  return { elements, calls, stored };
}

(async () => {
  // 1) 缺少服务地址时拒绝提交
  let popup = loadPopup();
  await popup.elements.connect.listeners.click();
  assert.match(popup.elements.status.textContent, /服务地址/);
  assert.equal(popup.calls.sent.length, 0);

  // 2) 非 HTTPS 且非本机地址被拒绝
  popup = loadPopup({ stored: { "fitsync.token": "t" } });
  popup.elements.service.value = "http://example.com/api";
  await popup.elements.connect.listeners.click();
  assert.match(popup.elements.status.textContent, /HTTPS/);
  assert.equal(popup.calls.sent.length, 0);

  // 3) 缺少令牌时拒绝，不发起后台请求
  popup = loadPopup({ stored: { "fitsync.service": "http://127.0.0.1:8080/api" } });
  await popup.elements.connect.listeners.click();
  assert.match(popup.elements.status.textContent, /令牌/);
  assert.equal(popup.calls.sent.length, 0);

  // 4) 正常路径：保存设置、授予域名权限、交给后台统一登录
  popup = loadPopup({
    stored: { "fitsync.service": "http://127.0.0.1:8080/api", "fitsync.token": "token" },
  });
  await popup.elements.connect.listeners.click();
  const sent = popup.calls.sent[0];
  assert.equal(sent.type, "fitsync.connect");
  assert.equal(sent.platform, "STRAVA");
  assert.equal(sent.service, "http://127.0.0.1:8080/api");
  assert.equal(sent.token, "token");
  assert.equal(sent.url, "https://www.strava.com/login");
  assert.equal(popup.calls.permissionRequests[0], "http://127.0.0.1:8080/*");
  assert.match(popup.elements.status.textContent, /已连接/);

  // 5) 后台失败原因原样展示
  popup = loadPopup({
    stored: { "fitsync.service": "http://127.0.0.1:8080/api", "fitsync.token": "token" },
    backgroundResult: { ok: false, error: "Strava 网页登录已失效，请重新登录" },
  });
  await popup.elements.connect.listeners.click();
  assert.match(popup.elements.status.textContent, /登录已失效/);
  assert.equal(popup.elements.status.className, "status error");

  // 6) 清除令牌后不再回填
  popup = loadPopup({
    stored: { "fitsync.service": "http://127.0.0.1:8080/api", "fitsync.token": "token" },
  });
  await popup.elements.logout.listeners.click();
  assert.equal(popup.stored["fitsync.token"], undefined);
  assert.equal(popup.elements.token.value, "");

  console.log("browser-extension popup: all checks passed");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
