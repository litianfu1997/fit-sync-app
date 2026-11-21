package com.fitsync.cloud;

import com.fitsync.cloud.connector.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  private final CloudStore store;
  private final CryptoService crypto;
  private final Map<String, PlatformConnector> connectors;

  public AccountService(CloudStore store, CryptoService crypto) {
    this(store, crypto, "", "");
  }

  @Autowired
  public AccountService(
      CloudStore store,
      CryptoService crypto,
      @Value("${fitsync.strava.client-id:}") String stravaClientId,
      @Value("${fitsync.strava.client-secret:}") String stravaClientSecret) {
    this.store = store;
    this.crypto = crypto;
    connectors =
        Map.of(
            "COROS", new CorosConnector(),
            "IGPSPORT", new IgpsportConnector(),
            "ONELAP", new OnelapConnector(),
            "XINGZHE", new XingzheConnector(),
            "GARMIN", new GarminConnector(),
            "STRAVA", new StravaConnector(stravaClientId, stravaClientSecret));
  }

  public PlatformConnector connector(String platform) {
    var result = connectors.get(platform);
    if (result == null) throw ApiException.bad("该平台暂未提供云端自动连接，请使用 FIT 文件导入导出");
    return result;
  }

  public List<Map<String, Object>> list(String user) {
    return store
        .db
        .queryForList(
            "SELECT id,user_id,platform,display_name,status,last_sync_at,last_error,encrypted_session"
                + " FROM platform_account WHERE user_id=? ORDER BY platform",
            user)
        .stream()
        .map(this::publicAccount)
        .toList();
  }

  private Map<String, Object> publicAccount(Map<String, Object> row) {
    var r = new LinkedHashMap<String, Object>();
    String platform = CloudStore.str(row, "platform");
    boolean pullSupported = true;
    try {
      pullSupported = !connector(platform).skipPullAsDestination(session(row));
    } catch (Exception ignored) {
    }
    r.put("id", row.get("id"));
    r.put("platform", platform);
    r.put("displayName", row.get("display_name"));
    r.put("status", row.get("status"));
    r.put("lastSyncAt", row.get("last_sync_at"));
    r.put("lastError", row.get("last_error"));
    r.put("pullSupported", pullSupported);
    return r;
  }

  public synchronized Map<String, Object> connectWeb(
      String user, String platform, String displayName, Map<String, String> fields) {
    platform = platform == null ? "" : platform.toUpperCase(Locale.ROOT);
    if (fields == null || fields.size() > 16) throw ApiException.bad("网页会话信息不完整或过长");
    String name = displayName == null || displayName.isBlank()
        ? platformName(platform) + " · 网页账号"
        : displayName.trim();
    if (name.length() > 160 || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0)
      throw ApiException.bad("账号显示名称无效");
    var result = connector(platform).connectWeb(fields);
    if (result.status() != ConnectorResult.Status.SUCCESS || result.value() == null)
      throw ApiException.bad(result.message() == null ? "网页会话验证失败" : result.message());
    return persist(user, platform, name, result.value());
  }

  private static String platformName(String platform) {
    return switch (platform) {
      case "ONELAP" -> "迈金";
      case "XINGZHE" -> "行者";
      case "GARMIN" -> "Garmin 中国站";
      case "STRAVA" -> "Strava";
      default -> platform;
    };
  }

  public synchronized Map<String, Object> persistOAuth(
      String user, String platform, String displayName, Session connected) {
    platform = platform == null ? "" : platform.toUpperCase(Locale.ROOT);
    String name =
        displayName == null || displayName.isBlank()
            ? platformName(platform) + " · 授权账号"
            : displayName.trim();
    if (name.length() > 160 || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0)
      name = platformName(platform) + " · 授权账号";
    return persist(user, platform, name, connected);
  }

  public synchronized Map<String, Object> connect(
      String user, String platform, String username, String password, boolean save) {
    if (username == null
        || username.isBlank()
        || username.length() > 160
        || password == null
        || password.isBlank()
        || password.length() > 512) throw ApiException.bad("请输入有效的平台账号和密码");
    platform = platform == null ? "" : platform.toUpperCase(Locale.ROOT);
    var result = connector(platform).login(username, password, save);
    if (result.status() != ConnectorResult.Status.SUCCESS || result.value() == null)
      throw ApiException.bad(result.message() == null ? "平台登录失败" : result.message());
    return persist(user, platform, username, result.value());
  }

  private Map<String, Object> persist(
      String user, String platform, String displayName, Session connected) {
    var existing =
        store.db.queryForList(
            "SELECT id FROM platform_account WHERE user_id=? AND platform=?", user, platform);
    if (!existing.isEmpty()
        && store.db.queryForObject(
                "SELECT COUNT(*) FROM account_lease WHERE account_id=? AND lease_until>?",
                Integer.class,
                existing.getFirst().get("id"),
                System.currentTimeMillis())
            > 0) throw ApiException.bad("平台账号正在同步，请稍后重新连接");
    String id = existing.isEmpty()
        ? CloudStore.id() : CloudStore.str(existing.getFirst(), "id");
    if (!existing.isEmpty()) {
      var previous = store.owned("platform_account", id, user);
      var old = session(previous);
      String oldId = old.externalUserId(), newId = connected.externalUserId();
      boolean different =
          oldId != null && newId != null
              ? !oldId.equals(newId)
              : !Objects.equals(previous.get("display_name"), displayName);
      if (different) throw ApiException.bad("检测到不同的平台账号，请先解绑原账号，再连接新账号");
    }
    String encrypted = encodeSession(user, id, connected);
    if (existing.isEmpty())
      store.db.update(
          "INSERT INTO platform_account(id,user_id,platform,display_name,status,encrypted_session)"
              + " VALUES(?,?,?,?,?,?)",
          id,
          user,
          platform,
          displayName,
          "CONNECTED",
          encrypted);
    else
      store.db.update(
          "UPDATE platform_account SET"
              + " display_name=?,status='CONNECTED',last_error=NULL,encrypted_session=?,encrypted_credentials=NULL"
              + " WHERE id=? AND user_id=?",
          displayName,
          encrypted,
          id,
          user);
    return publicAccount(store.owned("platform_account", id, user));
  }

  private String encodeSession(String user, String id, Session session) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("accessToken", session.accessToken());
    value.put("externalUserId", session.externalUserId());
    value.put("storeCredentials", session.storeCredentials());
    value.put("attributes", session.attributes());
    if (session.storeCredentials()) {
      value.put("username", session.username());
      value.put("password", session.password());
    }
    return crypto.encrypt(store.encode(value), user + ":" + id);
  }

  public Session session(Map<String, Object> account) {
    String user = CloudStore.str(account, "user_id"), id = CloudStore.str(account, "id");
    var data =
        store.decode(crypto.decrypt(CloudStore.str(account, "encrypted_session"), user + ":" + id));
    Map<String, String> attributes = new LinkedHashMap<>();
    if (data.get("attributes") instanceof Map<?, ?> values) {
      values.forEach((key, value) -> {
        if (key != null && value != null) attributes.put(key.toString(), value.toString());
      });
    }
    Session session =
        new Session(
            CloudStore.str(data, "accessToken"),
            CloudStore.str(data, "externalUserId"),
            CloudStore.str(data, "username"),
            CloudStore.str(data, "password"),
            Boolean.TRUE.equals(data.get("storeCredentials")),
            attributes);
    // 刷新令牌会轮换且旧值立即失效，令牌一变就落库，避免进程中断后账号只能重新授权。
    AtomicReference<String> base =
        new AtomicReference<>(CloudStore.str(account, "encrypted_session"));
    session.onTokensChanged(() -> saveSession(account, session, base));
    return session;
  }

  public void saveSession(Map<String, Object> account, Session session) {
    store.db.update(
        "UPDATE platform_account SET encrypted_session=? WHERE id=? AND user_id=? AND"
            + " encrypted_session=?",
        encodeSession(CloudStore.str(account, "user_id"), CloudStore.str(account, "id"), session),
        account.get("id"),
        account.get("user_id"),
        account.get("encrypted_session"));
  }

  /**
   * 令牌轮换时的增量保存：用自己的密文基线，既能连续保存，又不会让过期快照覆盖新凭据。
   */
  private void saveSession(
      Map<String, Object> account, Session session, AtomicReference<String> base) {
    String encrypted =
        encodeSession(CloudStore.str(account, "user_id"), CloudStore.str(account, "id"), session);
    if (store.db.update(
            "UPDATE platform_account SET encrypted_session=? WHERE id=? AND user_id=? AND"
                + " encrypted_session=?",
            encrypted,
            account.get("id"),
            account.get("user_id"),
            base.get())
        > 0) base.set(encrypted);
  }

  @Transactional
  public void delete(String user, String id) {
    store.owned("platform_account", id, user);
    String owner = CloudStore.id();
    long now = System.currentTimeMillis();
    if (store.db.update(
            "UPDATE account_lease SET lease_owner=?,lease_until=? WHERE account_id=? AND"
                + " lease_until<?",
            owner,
            now + 120000,
            id,
            now)
        == 0) {
      // 锁住账号行并重查，避免在检查和删除之间有 Worker 成功领取租约。
      store.db.queryForObject(
          "SELECT id FROM platform_account WHERE id=? AND user_id=? FOR UPDATE",
          String.class,
          id,
          user);
      if (store.db.queryForObject(
              "SELECT COUNT(*) FROM account_lease WHERE account_id=?", Integer.class, id)
          > 0) throw ApiException.bad("平台账号正在同步，请稍后解绑");
      store.db.update(
          "INSERT INTO account_lease(account_id,lease_owner,lease_until) VALUES(?,?,?)",
          id,
          owner,
          now + 120000);
    }
    store.db.update("DELETE FROM sync_job WHERE account_id=? AND user_id=?", id, user);
    store.db.update("DELETE FROM sync_rule WHERE destination_account_id=? AND user_id=?", id, user);
    store.db.update("DELETE FROM remote_activity_skip WHERE account_id=? AND user_id=?", id, user);
    store.db.update("DELETE FROM platform_copy WHERE account_id=? AND user_id=?", id, user);
    store.db.update("DELETE FROM account_lease WHERE account_id=? AND lease_owner=?", id, owner);
    store.db.update("DELETE FROM platform_account WHERE id=? AND user_id=?", id, user);
  }
}
