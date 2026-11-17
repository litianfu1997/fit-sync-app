/**
 * 扩展弹窗：网页发起的“弹窗登录并自动连接”已经由 bridge.js 完成。
 * 这里只保留备用入口：当用户在 FitSync 网页之外时，可以打开平台登录页，
 * 并把当前服务的会话连接请求转发给后台同一套受控流程。
 */
const PLATFORMS = {
  STRAVA: { label: "Strava", loginUrl: "https://www.strava.com/login" },
  GARMIN: { label: "Garmin Connect 中国站", loginUrl: "https://connect.garmin.cn/modern/" },
  ONELAP: { label: "迈金 ONELAP", loginUrl: "https://otm.onelap.cn/recordPage" },
  XINGZHE: {
    label: "行者",
    loginUrl: "https://www.imxingzhe.com/login/",
  },
};

const SERVICE_KEY = "fitsync.service";
const TOKEN_KEY = "fitsync.token";
const $ = (id) => document.getElementById(id);

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

function showStatus(message, error = false) {
  const node = $("status");
  node.textContent = message;
  node.className = error ? "status error" : "status";
}

async function render() {
  const stored = await chrome.storage.local.get([SERVICE_KEY, TOKEN_KEY]);
  $("service").value = stored[SERVICE_KEY] || "";
  $("token").value = stored[TOKEN_KEY] ? "••••••••（已保存）" : "";
  $("platform").innerHTML = Object.entries(PLATFORMS)
    .map(([key, value]) => `<option value="${key}">${value.label}</option>`)
    .join("");
  updateHint();
}

function updateHint() {
  const platform = PLATFORMS[$("platform").value];
  $("login-link").textContent = `打开 ${platform.label} 登录`;
}

async function saveSettings() {
  const base = serviceBase($("service").value);
  const tokenInput = $("token").value.trim();
  const stored = await chrome.storage.local.get([TOKEN_KEY]);
  const token = tokenInput.startsWith("••") ? stored[TOKEN_KEY] : tokenInput;
  if (!token) throw new Error("请填写 FitSync 登录令牌");
  // 扩展后台请求该服务前，需要用户授予对应域名权限。
  const origin = new URL(base).origin + "/*";
  if (!(await chrome.permissions.contains({ origins: [origin] }))) {
    const granted = await chrome.permissions.request({ origins: [origin] });
    if (!granted) throw new Error("需要授权访问该 FitSync 服务域名才能提交会话");
  }
  await chrome.storage.local.set({ [SERVICE_KEY]: base, [TOKEN_KEY]: token });
  return { base, token };
}

/** 备用入口：没有从 FitSync 网页发起时，也走同一套受控登录与提交流程。 */
async function connect() {
  showStatus("正在打开登录窗口…");
  try {
    const { base, token } = await saveSettings();
    const platformKey = $("platform").value;
    const result = await chrome.runtime.sendMessage({
      type: "fitsync.connect",
      platform: platformKey,
      service: base,
      token,
      url: PLATFORMS[platformKey].loginUrl,
      fromPopup: true,
    });
    if (!result?.ok) throw new Error(result?.error || "连接失败，请重试");
    showStatus(`已连接 ${result.displayName || PLATFORMS[platformKey].label}。`);
  } catch (error) {
    showStatus(error?.message || "连接失败，请重试", true);
  }
}

$("platform").addEventListener("change", updateHint);
$("login-link").addEventListener("click", (event) => {
  event.preventDefault();
  chrome.tabs.create({ url: PLATFORMS[$("platform").value].loginUrl });
});
$("connect").addEventListener("click", connect);
$("save").addEventListener("click", async () => {
  try {
    await saveSettings();
    showStatus("服务地址与令牌已保存。");
  } catch (error) {
    showStatus(error?.message || "保存失败", true);
  }
});
$("logout").addEventListener("click", async () => {
  await chrome.storage.local.remove([TOKEN_KEY]);
  $("token").value = "";
  showStatus("已清除本机保存的 FitSync 令牌。");
});
render();
