# FitSync Cloud · Android 上架 Google Play 指南

本文说明如何在 `codex/wechat-web` 分支构建、配置并向 Google Play 上架在线版 Android 客户端（`cloud` flavor）。

## 1. 工程结构说明

| 变体 | 包名（默认） | targetSdk | 运行方式 |
| --- | --- | :---: | --- |
| `local` | `com.fitsync.app` | 34 | 纯本机离线版（Room + WorkManager + FIT 引擎） |
| `cloud` | `com.fitsync.cloud` | 36 | 在线云端壳（加载远端 H5 实现热更新 + 原生分享导入/导出 + Custom Tabs OAuth + 可开关 AdMob） |

`cloud` 变体代码位于 `app/src/cloud/`，并在 `app/src/cloud/AndroidManifest.xml` 中移除了 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`、`FOREGROUND_SERVICE_DATA_SYNC`、`RECEIVE_BOOT_COMPLETED` 等本地后台保活权限。

## 2. 配置远端 H5 地址与 AdMob

在仓库根目录创建被 `.gitignore` 忽略的 `cloud.properties`（或通过环境变量 / GitHub Actions Variables 传入）：

```properties
# 线上 H5 入口（必须 https://，结尾不要带 /；决定热更新地址）
webUrl=https://your-domain.example.com
# 可选，默认等于 ${webUrl}/api
# apiBaseUrl=https://your-domain.example.com/api
# 可选，默认 com.fitsync.cloud
# applicationId=com.fitsync.cloud

# AdMob 默认关闭（先不开广告）
admobEnabled=false
# 发布 Google Play 前，即使先不开广告也建议填写自己的真实 AdMob App ID（未填写时构建会回退 Google 官方测试 App ID）
admobAppId=ca-app-pub-xxxxxxxxxxxxxxxx~yyyyyyyyyy
admobBannerUnitId=
```

对应环境变量：`FITSYNC_CLOUD_WEB_URL`、`FITSYNC_CLOUD_API_BASE_URL`、`FITSYNC_CLOUD_APPLICATION_ID`、`FITSYNC_ADMOB_ENABLED`、`FITSYNC_ADMOB_APP_ID`、`FITSYNC_ADMOB_BANNER_UNIT_ID`。

### 如何后续开启广告

1. 构建时确保 `admobEnabled=true`（或 `FITSYNC_ADMOB_ENABLED=true`）并配置真实的 `admobAppId`。
2. 在服务端 `.env.cloud` 配置：
   ```env
   ADMOB_ENABLED=true
   ADMOB_BANNER_AD_UNIT_ID=ca-app-pub-xxxxxxxxxxxxxxxx/zzzzzzzzzz
   ```
3. 只要 `ADMOB_ENABLED`（构建期）和服务端 `/api/v1/config` 的 `admob.enabled` 任意一方为 `false`，客户端就不会初始化 `MobileAds` 也不会展示广告位。

## 3. H5 热更新与原生能力衔接

- **热更新**：`cloud` 变体直接加载 `webUrl`，配合 `deploy/nginx.conf` 对 `index.html` / `sw.js` 的 `no-cache, must-revalidate` 与 `/assets/` 的长期缓存策略，更新前端静态包无需重新发布 `.aab`。
- **OAuth 登录（Google / Strava）**：Android WebView 禁止内嵌 Google OAuth，客户端会在 `shouldOverrideUrlLoading` 拦截跳转并拉起 **Chrome Custom Tabs**，授权完成后通过 `fitsync-cloud://oauth` 回跳到 `CloudMainActivity`。
- **FIT 导入导出**：
  - 系统分享 / 打开 `.fit` 文件会由 `CloudShareReceiverActivity` 直接上传至 `POST /api/v1/activities/import`（使用 Android Keystore 加密保存的 Bearer 会话）。
  - 详情页“导出原始 FIT”通过 `FitSyncNative.downloadFit` 由原生 OkHttp 下载并用系统 `ShareSheet` 导出。

## 4. 构建 Google Play 发布包（`.aab`）

### 本地构建

```powershell
$env:JAVA_HOME = "D:\jdk-21.0.10"
$env:ANDROID_HOME = "C:\Android\sdk"
.\gradlew.bat :app:testLocalDebugUnitTest :app:testCloudDebugUnitTest
.\gradlew.bat :app:bundleCloudRelease
```

签名使用仓库根目录的 `keystore.properties`（或 `FITSYNC_KEYSTORE_*` 环境变量），产物位于 `app/build/outputs/bundle/cloudRelease/app-cloud-release.aab`。

### GitHub Actions 构建

使用工作流 [`.github/workflows/build-cloud-aab.yml`](../.github/workflows/build-cloud-aab.yml)（手动触发 `workflow_dispatch`），构建完成后在 Actions Artifacts 下载 `fitsync-cloud-aab`。

## 5. Google Play 上架核对清单

1. **服务端生产环境**：确认已配置 HTTPS 域名，`SPRING_PROFILES_ACTIVE=prod`（强制关闭 `devLoginEnabled`）。
2. **账号删除合规**：已在后端提供 `DELETE /api/v1/me` 及设置页“注销并删除账号”入口；同时需在 Play Console 填写一个对外可访问的隐私政策页面与账号删除说明链接。
3. **测试账号**：在 Play Console「应用访问权限」填写预先导入演示数据的正式测试账号和密码，供审核员登录验证全部功能。
4. **广告与 AD_ID 声明**：由于 `cloud` 变体已集成 `play-services-ads` SDK，Play Console 提交时请按实际情况核对「是否含广告」与 `AD_ID` 数据安全声明；若首版完全不启用广告且希望彻底移除 `AD_ID` 权限，可在 `app/src/cloud/AndroidManifest.xml` 显式添加 `<uses-permission android:name="com.google.android.gms.permission.AD_ID" tools:node="remove" />`。
5. **新个人账号测试**：若使用新注册的 Google Play 个人开发者账号，上线正式版前需先在 Play Console 完成封闭测试要求。
