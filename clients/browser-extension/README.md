# FitSync 会话助手（浏览器扩展）

桌面 Chrome / Edge 扩展。用于解决一个普通网页无法做到的事：**读取你自己浏览器里已登录平台的 HttpOnly Cookie**。

## 它解决的流程

1. 在 FitSync 网页的「同步中心 → 网页会话连接」中点击「弹窗登录并自动读取」。
2. 扩展弹出一个平台官方登录小窗，你在其中正常登录，验证码/二次验证也在这个窗口里完成。
3. 扩展检测到登录会话后读取 Cookie（迈金额外读取 `token` / `refresh_token` / `uid`）。
4. 扩展通过 FitSync API 提交会话，服务端加密保存，窗口自动关闭，网页刷新平台状态。

扩展**不保存平台 Cookie**，不采集浏览历史，不注入远程代码，也不绕过验证码或二次验证。

## 安装（开发版）

1. Chrome 打开 `chrome://extensions`，Edge 打开 `edge://extensions`。
2. 开启「开发者模式」。
3. 选择「加载已解压的扩展程序」，指向本目录 `clients/browser-extension`。
4. 记下扩展 ID（形如 `abcdefghijklmnop...`）。

## 配置 FitSync 网页

扩展 ID 因安装路径和浏览器而异，必须显式告诉网页：

```powershell
# clients/uniapp/.env.local
VITE_FITSYNC_EXTENSION_ID=你的扩展ID
```

同时扩展的 `manifest.json` 中 `externally_connectable.matches` 必须包含 FitSync 网页来源，例如：

```json
"matches": ["http://127.0.0.1/*", "http://localhost/*", "https://你的域名/*"]
```

默认已放行本机开发地址与全部 HTTPS 站点；正式发布前应收敛为你的实际域名。

## CORS 与来源

扩展后台以 `chrome-extension://<id>` 作为 Origin 发起请求，因此 Java 服务必须显式放行该来源：

```powershell
$env:FITSYNC_CORS_ORIGINS = "http://127.0.0.1:5273,chrome-extension://你的扩展ID"
```

未配置时，扩展提交会收到 `403 Invalid CORS request`；这是预期行为，不应通过关闭鉴权或放开所有来源解决。

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `cookies` | 仅读取 manifest 列出的平台站点 Cookie；用于获取登录会话 |
| `host_permissions` | 限定在 Strava、Garmin 中国站、迈金、行者四个平台 |
| `scripting` / `tabs` / `windows` | 打开受控登录小窗，并在迈金页面读取 localStorage 中的 token |
| `storage` | 保存你填写的 FitSync 服务地址与登录令牌 |
| `optional_host_permissions` | 首次连接时向你申请访问你的 FitSync 服务域名 |

扩展不请求 `<all_urls>`，也不会把平台 Cookie 发送到 FitSync 之外的域名。

## 测试

```powershell
node clients/browser-extension/test/popup.test.js
node clients/browser-extension/test/bridge.test.js
```

测试使用内存模拟 `chrome.*` 与 `fetch`，不访问真实平台，也不读取真实 Cookie。

## 已知限制

- 仅桌面浏览器可用；微信小程序和手机浏览器仍使用手动复制方式。
- 平台登录页若要求人机验证，必须在弹出窗口内由本人完成，扩展不会代替或绕过。
- Cookie 会过期；失效后网页会显示需要重新验证，再次点击即可重新弹窗登录。
- 扩展尚未上架商店，当前为开发者模式加载；正式分发需按 Chrome Web Store 政策提交审核并补充隐私说明。
