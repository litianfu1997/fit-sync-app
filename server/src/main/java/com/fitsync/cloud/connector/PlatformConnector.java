package com.fitsync.cloud.connector;

/** 无用户全局状态；每次调用均传入当前账号解密后的会话。 */
public interface PlatformConnector {
  String platform();

  ConnectorResult<Session> login(String username, String password, boolean storeCredentials);

  ConnectorResult<ActivityPage> listActivities(Session session, long sinceEpochMs, String cursor);

  ConnectorResult<FitDownload> downloadFit(Session session, RemoteActivity activity);

  ConnectorResult<UploadResult> uploadFit(Session session, byte[] fitBytes, String fileName);

  ConnectorResult<UploadResult> checkUpload(Session session, String confirmationToken);
}
