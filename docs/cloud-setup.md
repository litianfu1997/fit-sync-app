# FitSync 云同步启动与部署

实现日期：2026-11-09。分支：`codex/wechat-web`。本工程是与 Android 本机版分开的云端产品线，采用免费使用与广告变现方案。

## 工程入口

| 目录 / 文件 | 作用 |
| --- | --- |
| `pom.xml` | Maven 聚合入口，构建共享核心和 Java 后端，不调用 Android Gradle |
| `fit-core/` | 白名单复用现有 Kotlin FIT / 去重源码，并提供 Java 接口；没有 Android / Room 依赖 |
| `server/` | Java 21、Spring Boot 3.5.16、JdbcTemplate、Flyway、持久化同步队列 |
| `clients/uniapp/` | Vue 3 / TypeScript，输出 H5 与微信小程序 |
| `scripts/cloud.ps1` | Windows 本地测试、构建、运行入口 |
| `compose.yaml` | PostgreSQL、Java 服务和 Nginx H5 容器配置 |

## 1. 本地运行

需要 JDK 21、Maven 3.6.3+、Node.js 20.19+ 和 npm。当前机器使用 `D:/jdk-21`、`D:/apache-maven-3.9.16`、Node.js 24。不要覆盖 `ANDROID_HOME`。

在仓库根执行：

```powershell
./scripts/cloud.ps1 -Task run -JavaHome D:/jdk-21 -Maven D:/apache-maven-3.9.16/bin/mvn.cmd
```

此命令先构建并测试，随后运行 Java 服务。默认 `http://127.0.0.1:8080`，开发登录只在 `local` 配置启用，服务只监听本机。H2 文件库、原始 FIT 与本地生成的加密密钥位于被忽略的 `.data/`；Maven 缓存和运行副本位于 `.cache/`。

在另一个终端执行：

```powershell
Set-Location clients/uniapp
npm.cmd ci
npm.cmd run dev:h5
```

打开 [本地网页](http://127.0.0.1:5273)（端口由 `clients/uniapp/vite.config.ts` 的 `server.port` 决定）。H5 的 `/api` 经 Vite 代理到 Java 服务。可直接用用户名密码**注册**正式业务账号（Web 与小程序通用），也可选择本地开发登录：后者每次登录会新建一个独立用户，页面刷新保留同一会话，但仅用于开发，不是正式账号找回机制。

后端 CORS 白名单必须包含页面来源，否则登录/注册会返回 `403 Invalid CORS request`（`GET /api/v1/config` 同样失败）。本地默认已放行 5273 与 5173。若改用其他端口，例如 `npm.cmd run dev:h5 -- --port 4180`，要在启动 Java 前设置 `FITSYNC_CORS_ORIGINS=http://127.0.0.1:4180,http://localhost:4180`。变更后重启服务；跨域错误不能通过放开所有来源或关闭鉴权来解决。

已有构建产物时，`cloud.ps1 -Task run -SkipBuild ...` 可跳过构建。也可按[后端说明](../server/README.md)直接启动 JAR。

## 2. 不需要真实账号的验收

在仓库根生成合成 FIT：

```powershell
python scripts/generate-sample-fit.py
```

生成 `.cache/samples/sample-ride.fit`，不包含任何真实账号或私人路线。登录后进入「活动」选择文件并导入，可查看指标、轨迹并下载原始 FIT；再次导入同一文件应提示已有记录，不新增活动。

「同步中心」可连接六平台：COROS、iGPSPORT 中国站使用账号密码并明确勾选授权；迈金、行者、Garmin 中国站使用网页会话；Strava 使用官方 OAuth 2.0 跳转授权，支持拉取活动（依据官方 Streams 接口重建 FIT）与上传。网页会话通过手动方式导入：在浏览器登录平台，从开发者工具复制 Cookie（迈金另需 token，Garmin 可补充 CSRF/SSO Cookie），确认交由当前 FitSync 云服务加密保存。

无论哪种方式，会话值都不会写入 FitSync 网页的存储、URL 或日志。

Garmin 登录页由 Cloudflare Turnstile 保护，账号密码不能交给云端自动登录；二次认证和验证码必须在平台官方页面由本人完成。GitHub stub／集成测试不能替代真实账号验证，首次部署应先用少量活动验证拉取、下载、上传与续期。无账号时可以验证活动导入和查询，但不能据此判断远端同步已成功。

疑似重复活动会暂停自动投递。可在详情查看候选，明确确认「保留为独立活动」后恢复投递；本版尚未实现跨活动人工合并。

## 3. 微信小程序

1. 将真实 AppID 填入 `clients/uniapp/src/manifest.json` 的 `mp-weixin.appid`。
2. 在客户端 `.env.local` 设置 `VITE_API_BASE_URL=https://你的域名/api`。
3. Java 服务配置同一小程序的 `WECHAT_APP_ID`、`WECHAT_APP_SECRET`。
4. 在微信后台配置 request、uploadFile、downloadFile 合法 HTTPS 域名。
5. 执行 `npm.cmd run build:mp-weixin`，在微信开发者工具导入 `dist/build/mp-weixin`。

仓库不包含真实 AppID、AppSecret 或广告位 ID。客户端环境变量只能包含公开配置，不能放微信 AppSecret、数据库密码或凭据加密密钥。

Web 与小程序都支持用户名密码注册登录，同一账号跨端通用，不依赖微信配置。小程序配置微信后额外提供一键登录，可在「设置 → 账号与登录方式」绑定微信或补设密码，把一键登录和密码登录统一到同一业务用户。微信登录与绑定流程需要真实微信环境联调。

## 4. 广告

流量主获准后，在 Java 服务设置 `WECHAT_NATIVE_AD_UNIT_ID`。微信构建会根据 `/api/v1/config` 返回值显示真实原生广告；无配置、无填充或加载失败时不影响基础功能。H5 不使用微信小程序广告组件。

客户端包含可选激励视频的交互基础，但后端在可信广告验证接通前返回禁用配置，不会因客户端声称观看完成而增加额度，也不会伪造收益。首版收入接入以原生广告为主，实际准入和结算以流量主后台为准。

## 5. 生产容器配置

复制 `.env.cloud.example` 为 `.env.cloud`，填入：

| 变量 | 说明 |
| --- | --- |
| `POSTGRES_PASSWORD` | 新建云端数据库的密码 |
| `FITSYNC_ENCRYPTION_KEY` | Base64 编码的 32 字节随机密钥，来自你的密钥管理配置 |
| `WECHAT_APP_ID` / `WECHAT_APP_SECRET` | 同一真实小程序的配置 |
| `WECHAT_NATIVE_AD_UNIT_ID` | 可选，审核获准的原生广告位 |
| `FITSYNC_CORS_ORIGINS` | 明确允许的网页来源；同源网关无需任意开放 |
| `STRAVA_CLIENT_ID` / `STRAVA_CLIENT_SECRET` | 可选，Strava 开发者应用凭据；配置后网页端自动隐藏手填输入框，直接使用开发者应用跳转官方 OAuth 授权 |
| `STRAVA_REDIRECT_URI` | 可选，公网反向代理下建议显式指定 `https://你的域名/api/v1/accounts/oauth/strava/callback` |

```powershell
docker compose --env-file .env.cloud up --build -d
```

H5 网关监听 `127.0.0.1:8090`，将 `/api` 转发给 Java 服务。对外部署时由你的 HTTPS 网关代理此入口；小程序使用同一公开 HTTPS 域名。Java 与 PostgreSQL 不直接发布宿主机端口。

`prod` 配置强制关闭开发登录，缺数据库、微信凭据或加密密钥时拒绝启动。数据库通过 Flyway 脚本初始化，容器使用独立 PostgreSQL 与 FIT 卷；不要指向现有 Android 或其他业务数据库。生产密钥丢失将无法解密平台会话，需和数据库、私有文件分别妥善保管。

本版原件存储使用私有持久卷，尚未提供 S3 / OSS 存储适配、用户注销和数据生命周期清理。Docker / PostgreSQL 部署配置已提供，当前本机未安装 Docker，尚未执行容器或真实 PostgreSQL 验证。

## 6. 构建与验证

```powershell
./scripts/cloud.ps1 -Task test -JavaHome D:/jdk-21 -Maven D:/apache-maven-3.9.16/bin/mvn.cmd
Set-Location clients/uniapp
npm.cmd run typecheck
npm.cmd run build:h5
npm.cmd run build:mp-weixin
```

当前自动化测试覆盖核心解析、严格 CRC / ZIP 边界、跨用户隔离、会话与凭据保护、用户名密码注册登录与 PBKDF2 散列、绑定状态、去重与复核、规则变更、历史窗口、持久队列及 HTTP stub 平台协议。真实平台账号、微信真机 `code2session` 绑定与广告收入不属于这些测试已验证的范围。

2026-11-09 验收结果：66 项 JVM 测试全部通过，TypeScript 检查、H5 和微信小程序构建通过。浏览器已使用合成 FIT 验证登录、导入、重复去重、活动详情与轨迹、原件下载；下载原件的 SHA-256 与导入文件一致。手机视口无横向溢出，切换视口后可绘制轨迹，同步、任务与设置页面无控制台错误。

验收截图均使用合成数据：[桌面活动详情](assets/cloud/activity-desktop.png)、[手机轨迹视口](assets/cloud/activity-mobile.png)。

继续阅读：[前端说明](../clients/uniapp/README.md)、[后端说明](../server/README.md)、[跨端视觉规范](client-ui.md)、[完整方案与进度](wechat-web-plan.md)。
