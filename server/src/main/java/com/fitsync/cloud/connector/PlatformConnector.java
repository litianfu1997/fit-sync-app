package com.fitsync.cloud.connector;

/** 无用户全局状态；每次调用均传入当前账号解密后的会话。 */
public interface PlatformConnector {
  String platform();

  ConnectorResult<Session> login(String username, String password, boolean storeCredentials);

  /** 网页登录平台使用显式提交的会话；密码平台不需要实现此入口。 */
  default ConnectorResult<Session> connectWeb(java.util.Map<String, String> session) {
    return ConnectorResult.failure(
        ConnectorResult.Status.REQUIRES_USER_ACTION,
        "WEB_SESSION_UNSUPPORTED",
        "该平台请使用账号密码连接");
  }

  /** 是否在作为同步目标时跳过预拉取与定时拉取（如纯 OAuth 无法导出原始 FIT 时节省 API 读取配额）。 */
  default boolean skipPullAsDestination(Session session) {
    return false;
  }

  ConnectorResult<ActivityPage> listActivities(Session session, long sinceEpochMs, String cursor);

  ConnectorResult<FitDownload> downloadFit(Session session, RemoteActivity activity);

  ConnectorResult<UploadResult> uploadFit(Session session, byte[] fitBytes, String fileName);

  ConnectorResult<UploadResult> checkUpload(Session session, String confirmationToken);
}
