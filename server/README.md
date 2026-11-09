# FitSync Java 云端

Java 21、Spring Boot 3.5、JdbcTemplate 和 Flyway。与现有 Android 应用分别运行；不会读取或修改 Android 的 Room 数据库。

## 本地运行

在仓库根设置 JDK 21，然后运行：

```powershell
$env:JAVA_HOME = 'D:/jdk-21'
mvn -pl server -am install -DskipTests
mvn -f server/pom.xml spring-boot:run
```

默认 `local` 配置只监听 `127.0.0.1:8080`，使用当前工作目录 `.data/` 中的 H2 文件数据库、私有 FIT 文件及自动生成的开发加密密钥。Web 和小程序可使用用户名密码注册登录正式业务账号；开发登录每次创建独立随机用户，可通过前端保存的会话继续访问；退出并重新体验会建立新用户。开发登录入口只在 `local` 启用，生产可用密码账号。

测试使用内存 H2、测试专用密钥和 `target/test-fits`，不调用真实平台登录或上传。

```powershell
mvn -pl server -am verify
```

## 生产配置

设置 `SPRING_PROFILES_ACTIVE=prod`，并配置以下环境变量。生产配置缺少加密密钥或微信登录配置时拒绝启动；不能通过混用 `local,prod` 开启开发登录。

| 变量 | 用途 |
| --- | --- |
| `DATABASE_URL` | JDBC PostgreSQL 地址，例如 `jdbc:postgresql://postgres:5432/fitsync` |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | 数据库凭据 |
| `FITSYNC_ENCRYPTION_KEY` | Base64 编码的随机 32 字节 AES 密钥，与数据库分开保存并备份 |
| `FITSYNC_STORAGE_DIR` | 私有 FIT 持久目录，不交给 Nginx 静态托管 |
| `FITSYNC_CORS_ORIGINS` | 允许的 H5 域名，以逗号分隔，不能使用 `*` |
| `WECHAT_APP_ID` / `WECHAT_APP_SECRET` | 小程序登录与微信绑定配置，仅服务端使用；不影响用户名密码登录 |
| `WECHAT_NATIVE_AD_UNIT_ID` | 可选的真实原生广告位 |
| `PORT` | 默认 8080 |

通过 HTTPS 反向代理对外提供服务，配置代理只信任自己的网关，并将 API 访问日志设置为不记录请求正文、Authorization 或 `X-Login-Poll-Token`。密钥丢失后无法恢复平台会话；更换密钥前需完成解密重加密迁移。

## 已实现的执行边界

- Web 和微信小程序都支持用户名密码注册登录；密码用 PBKDF2-HMAC-SHA256 加盐散列保存，数据库不存明文。已有微信用户可在登录后补设密码，密码用户可在小程序绑定微信，使两端落到同一业务用户。小程序微信 `code2session` 一键登录、网页五分钟确认码登录继续保留；业务 Bearer 会话只保存 SHA-256 摘要。
- COROS 和 iGPSPORT 登录、拉取、下载、上传及异步确认通过连接器处理。它们仍属于实验性平台接入，真实账号、云端 IP 和平台风控尚需在部署环境验证。其他平台提供 FIT 文件入口。
- 账号令牌与用户明确同意托管的密码使用 AES-256-GCM 加密，并绑定用户和账号 ID。未同意托管密码时仅保存令牌，过期后暂停并提示重新连接。
- 任务和分页游标持久化；账号租约保证串行，重试退避最大六小时。手动回填使用所选历史范围，规则定时拉取使用 48 小时增量重叠窗。
- 投递固定到入队时的文件副本，并同时约束文件幂等键与活动、目标账号唯一性。平台处理中只查询确认；进程在 POST 后崩溃而没有可查询标记时转为待用户核对，不会再次上传。
- FIT 校验、去重和原件下载按用户隔离；缺少有效原始 FIT 的远端活动跳过并在任务结果显示数量。疑似重复活动保留待复核状态，用户可查看候选后确认保持分离，随后恢复规则投递。
- 广告未配置时隐藏；激励广告的服务端验证未接入，因此不会向客户端发放额外额度，也不会记录虚构广告收益。基础同步保持可用。

当前使用私有磁盘目录保存 FIT，部署需挂载持久卷；对象存储适配、人工合并待复核活动、账号注销、忘记密码找回（需短信或邮箱服务）及文件保留策略属于后续运维与产品工作。用户名密码账号不提供自助找回，部署前应提示用户牢记登录用户名。
