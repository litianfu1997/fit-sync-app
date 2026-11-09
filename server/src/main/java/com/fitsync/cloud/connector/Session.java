package com.fitsync.cloud.connector;

/** 仅在一次账号任务内持有；调用方必须在调用后保存更新的 token，并对整个会话加密持久化。 只有用户显式允许 storeCredentials 才保留密码供无刷新接口的平台重新登录。 */
public final class Session {
  private String accessToken;
  private String externalUserId;
  private final String username;
  private final String password;
  private final boolean storeCredentials;

  public Session(
      String accessToken,
      String externalUserId,
      String username,
      String password,
      boolean storeCredentials) {
    this.accessToken = accessToken;
    this.externalUserId = externalUserId;
    this.storeCredentials = storeCredentials;
    this.username = storeCredentials ? username : null;
    this.password = storeCredentials ? password : null;
  }

  public String accessToken() {
    return accessToken;
  }

  public String externalUserId() {
    return externalUserId;
  }

  public String username() {
    return username;
  }

  public String password() {
    return password;
  }

  public boolean storeCredentials() {
    return storeCredentials;
  }

  public void updateFrom(Session refreshed) {
    this.accessToken = refreshed.accessToken;
    this.externalUserId = refreshed.externalUserId;
  }

  @Override
  public String toString() {
    return "Session[凭据已隐藏]";
  }
}
