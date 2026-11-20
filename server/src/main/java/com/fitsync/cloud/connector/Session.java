package com.fitsync.cloud.connector;

import java.util.Map;

/** 仅在一次账号任务内持有；调用方必须在调用后保存更新的 token，并对整个会话加密持久化。 只有用户显式允许 storeCredentials 才保留密码供无刷新接口的平台重新登录。 */
public final class Session {
  private String accessToken;
  private String externalUserId;
  private final String username;
  private final String password;
  private final boolean storeCredentials;
  private Map<String, String> attributes;
  private Runnable changeListener;

  public Session(
      String accessToken,
      String externalUserId,
      String username,
      String password,
      boolean storeCredentials) {
    this(accessToken, externalUserId, username, password, storeCredentials, Map.of());
  }

  public Session(
      String accessToken,
      String externalUserId,
      String username,
      String password,
      boolean storeCredentials,
      Map<String, String> attributes) {
    this.accessToken = accessToken;
    this.externalUserId = externalUserId;
    this.storeCredentials = storeCredentials;
    this.username = storeCredentials ? username : null;
    this.password = storeCredentials ? password : null;
    this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
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

  /** 网页 Cookie、CSRF 等同样属于敏感凭据，只能由 AccountService 加密持久化。 */
  public String attribute(String key) {
    return attributes.get(key);
  }

  public Map<String, String> attributes() {
    return attributes;
  }

  public void setAccessToken(String value) {
    this.accessToken = value;
    notifyChanged();
  }

  public void setAttribute(String key, String value) {
    var updated = new java.util.LinkedHashMap<>(attributes);
    if (value == null || value.isBlank()) updated.remove(key);
    else updated.put(key, value);
    attributes = Map.copyOf(updated);
    // 只有会轮换的刷新令牌需要立即落库；Cookie 等高频字段仍由任务结束时统一保存。
    if ("refreshToken".equals(key)) notifyChanged();
  }

  /**
   * 令牌变化后立即通知调用方落库。标准 OAuth（如 Strava）一旦换发新的 refresh token，
   * 旧值会立刻失效，因此新值不能等到任务结束才保存。
   */
  public void onTokensChanged(Runnable listener) {
    this.changeListener = listener;
  }

  private void notifyChanged() {
    Runnable listener = changeListener;
    if (listener != null) listener.run();
  }

  public void updateFrom(Session refreshed) {
    this.accessToken = refreshed.accessToken;
    if (refreshed.externalUserId != null) this.externalUserId = refreshed.externalUserId;
    this.attributes = refreshed.attributes;
    notifyChanged();
  }

  @Override
  public String toString() {
    return "Session[凭据已隐藏]";
  }
}
