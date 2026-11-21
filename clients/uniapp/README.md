# FitSync 小程序与网页

Vue 3 + TypeScript + uni-app 同一份代码构建微信小程序和 H5。全部业务数据来自 Java 服务，不包含演示活动或模拟平台登录。

视觉采用 Android 当前的橙色仪表风格，支持浅色、深色和跟随系统；颜色、字号、圆角与导航规则见[跨端视觉规范](../../docs/client-ui.md)。

## 本地启动

需要 Node.js 20.19+（本项目验证环境 Node.js 24）和 npm。先按 `server/README.md` 启动 Java 服务，然后在本目录执行：

```powershell
npm.cmd ci
Copy-Item .env.example .env.local
npm.cmd run dev:h5
```

浏览器打开 `http://127.0.0.1:5273`（端口见 `vite.config.ts` 的 `server.port`）。默认 `/api` 经 Vite 代理到 `http://127.0.0.1:8080`；后端端口变化时修改 `.env.local` 的 `VITE_DEV_PROXY_TARGET`。

页面来源需在后端 CORS 白名单内，否则浏览器直连代理后的 `/api` 会收到 `403 Invalid CORS request`。本地默认已放行 5273，换端口启动时要在启动 Java 服务前设置 `FITSYNC_CORS_ORIGINS`。

Web 和微信小程序都提供用户名密码**注册**与**登录**，同一账号在两端通用，登录令牌保留在本机，刷新页面不会新建用户。密码只提交一次、不落盘；服务端保存 PBKDF2 加盐散列，不保存明文。

本地开发登录由服务器 `devLoginEnabled` 控制，每次登录创建一个独立用户；开发登录不作为正式用户账号或恢复账号手段。可在「设置 → 账号与登录方式」为开发或微信用户补设用户名密码，也可修改昵称与密码。

## 微信小程序

1. 在 `src/manifest.json` 的 `mp-weixin.appid` 填入自己的真实小程序 AppID（仓库留空）。
2. 在 `.env.local` 设置 `VITE_API_BASE_URL=https://你的域名/api`。
3. 微信后台配置同一域名为 `request`、`uploadFile`、`downloadFile` 合法域名，使用有效 HTTPS 证书。
4. Java 服务配置同一个小程序的 AppID、AppSecret、凭据加密密钥及数据库，关闭开发登录。未配置微信登录时，小程序仍可用用户名密码注册登录；配置后额外提供微信一键登录与绑定。
5. 执行 `npm.cmd run build:mp-weixin`，在微信开发者工具导入 `dist/build/mp-weixin`。真机验证登录、聊天 FIT 选择、上传、文件分享和广告后再提交审核。

未填 AppID、没有云服务和合法域名时，构建产物不能作为已上线小程序使用。账号连接、类目、隐私声明、流量主申请须结合实际运营主体与微信后台审核配置。

## 网页登录

网页使用**用户名密码登录 / 注册**，与小程序共用同一 FitSync 账号；不依赖微信配置。

## 账号打通

| 起点 | 目标 | 操作 |
| --- | --- | --- |
| Web 用密码注册 | 小程序微信一键登录同一账号 | 小程序先用微信登录，或先在小程序用同一密码登录，再「设置 → 绑定当前微信」 |
| 小程序微信一键登录 | Web 用密码登录 | 小程序「设置 → 设置登录用户名与密码」，之后 Web 用该账号登录 |
| 开发体验用户 | 正式密码账号 | 「设置 → 设置登录用户名与密码」 |

所有入口都在「设置 → 账号与登录方式」中按当前账号状态显示；一个微信只能绑定一个 FitSync 用户。

## 页面与实际能力

- 概览：活动数、骑行距离、连接账号、待处理任务与最近活动。
- 活动：支持从已连接平台一键去重拉取活动并保存至云端（实时展示拉取与去重统计），支持关键词搜索、运动/待核对筛选、来源平台筛选与时间排序；支持 H5 本地文件选择 / 微信聊天文件选择上传 FIT；详情页支持交互式轨迹地图预览（H5 使用 OpenStreetMap 栅格瓦片，微信小程序使用经过 WGS-84 → GCJ-02 转换的原生 `<map>` 组件）、速度/海拔趋势曲线、点击进入全屏地图页缩放平移，以及多副本查看、平台同步状态与单条补发、原始 FIT 导出、待核对活动「合并到候选」或「保持分离」、云端活动删除。
- 同步：五个运动平台（COROS、iGPSPORT、迈金、行者、Garmin 中国站）支持直接在 Web 端与微信小程序端输入平台账号密码完成登录，由云端自动提取并加密保存登录态，无需手动复制 Cookie 或 Token；勾选托管凭据后可在会话失效时自动续登。Strava 使用官方 OAuth 2.0 跳转授权并自动续期令牌，支持拉取活动（重建 FIT）与上传。密码只在内存提交一次，提交后清空；托管凭据支持由用户勾选控制。
- 规则：来源 / 目标选择、历史补齐开关、启停与删除。开启规则即授权云端持续执行。
- 任务：分页状态、错误说明与手动重试，刷新查看实际后台结果。
- 设置：账号昵称、密码与绑定微信、连通性检查、切换服务器后清理登录态、退出提示云端规则仍继续运行。

API 地址统一以 `/api` 结尾，客户端追加 `/v1/...`，错误读取 `{code,message}`。401 清理登录态，网络失败显示重试和服务器设置入口。服务器设置保存在本机，切换地址会清除原令牌，避免向另一服务器发送现有登录凭证。

## 广告与免费使用

微信原生广告组件通过条件编译仅进入微信构建，只有 `/config` 的 `ads.enabled` 和 `nativeAdUnitId` 都有效时展示。无广告位不显示假广告，加载失败隐藏该广告。

自愿激励视频仅当服务器返回 `rewardedAdUnitId` 时出现；取消、无填充或加载失败均正常返回，不影响基础使用。客户端没有「看完即增加额度」逻辑，不把客户端播放完成事件视为可信的发奖凭证；后端未接入服务端验证时不发额度。H5 当前不投放微信广告。

## 构建与验证

```powershell
npm.cmd run typecheck
npm.cmd run build:h5
npm.cmd run build:mp-weixin
```

H5 产物为 `dist/build/h5`，小程序产物为 `dist/build/mp-weixin`。依赖锁定同一 DCloud 稳定批次，升级时所有 `@dcloudio/uni-*` 应一起升级。DCloud 当前 Sass 构建输出 legacy JS API 弃用提示，不影响产物。

H5 生产通过网关部署静态文件，并将 `/api` 反向代理至 Java 服务，避免把数据库、加密密钥或微信 AppSecret 放进前端环境变量。跨域部署需在后端显式配置允许的网页来源。

## 接口与参考

- 注册登录：`GET /config`、`POST /auth/register`、`POST /auth/login`、`POST /auth/dev-login`、`POST /auth/wechat`、`GET /me`、`PUT /me`。
- 账号打通：`POST /auth/wechat/bind`、`POST /auth/password`、`POST /auth/password/change`。
- 活动：`GET /dashboard`、`GET /activities`、`POST /activities/pull`、`GET /activities/pull`、`POST /activities/import`、`GET /activities/{id}`、`DELETE /activities/{id}`、`GET /activities/{id}/file`、`POST /activities/{id}/review`。
- 授权与同步：`/accounts`、`/rules`、`POST /sync`、`GET /jobs`、`POST /jobs/{id}/retry`。

路径均位于 `/api/v1` 下。开发与部署细节以 Java 服务 README 为准。

官方参考：[uni-app CLI](https://uniapp.dcloud.net.cn/quickstart-cli.html)、[登录](https://uniapp.dcloud.net.cn/api/plugins/login.html)、[网络请求](https://uniapp.dcloud.net.cn/api/request/request.html)。
