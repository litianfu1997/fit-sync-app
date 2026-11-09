package com.fitsync.cloud;

import com.fitsync.cloud.connector.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  private final CloudStore store;
  private final CryptoService crypto;
  private final Map<String, PlatformConnector> connectors;

  public AccountService(CloudStore store, CryptoService crypto) {
    this.store = store;
    this.crypto = crypto;
    connectors = Map.of("COROS", new CorosConnector(), "IGPSPORT", new IgpsportConnector());
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
            "SELECT id,platform,display_name,status,last_sync_at,last_error FROM platform_account"
                + " WHERE user_id=? ORDER BY platform",
            user)
        .stream()
        .map(this::publicAccount)
        .toList();
  }

  private Map<String, Object> publicAccount(Map<String, Object> row) {
    var r = new LinkedHashMap<String, Object>();
    r.put("id", row.get("id"));
    r.put("platform", row.get("platform"));
    r.put("displayName", row.get("display_name"));
    r.put("status", row.get("status"));
    r.put("lastSyncAt", row.get("last_sync_at"));
    r.put("lastError", row.get("last_error"));
    return r;
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
    var result = connector(platform).login(username, password, save);
    if (result.status() != ConnectorResult.Status.SUCCESS || result.value() == null)
      throw ApiException.bad(result.message() == null ? "平台登录失败" : result.message());
    var rows =
        store.db.queryForList(
            "SELECT id FROM platform_account WHERE user_id=? AND platform=?", user, platform);
    String id = rows.isEmpty() ? CloudStore.id() : CloudStore.str(rows.getFirst(), "id");
    if (!rows.isEmpty()) {
      var previous = store.owned("platform_account", id, user);
      var old = session(previous);
      String oldId = old.externalUserId(), newId = result.value().externalUserId();
      boolean different =
          oldId != null && newId != null
              ? !oldId.equals(newId)
              : !Objects.equals(previous.get("display_name"), username);
      if (different) throw ApiException.bad("检测到不同的平台账号，请先解绑原账号，再连接新账号");
    }
    String encrypted = encodeSession(user, id, result.value());
    if (rows.isEmpty())
      store.db.update(
          "INSERT INTO platform_account(id,user_id,platform,display_name,status,encrypted_session)"
              + " VALUES(?,?,?,?,?,?)",
          id,
          user,
          platform,
          username,
          "CONNECTED",
          encrypted);
    else
      store.db.update(
          "UPDATE platform_account SET"
              + " display_name=?,status='CONNECTED',last_error=NULL,encrypted_session=?,encrypted_credentials=NULL"
              + " WHERE id=? AND user_id=?",
          username,
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
    return new Session(
        CloudStore.str(data, "accessToken"),
        CloudStore.str(data, "externalUserId"),
        CloudStore.str(data, "username"),
        CloudStore.str(data, "password"),
        Boolean.TRUE.equals(data.get("storeCredentials")));
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
    store.db.update("DELETE FROM platform_copy WHERE account_id=? AND user_id=?", id, user);
    store.db.update("DELETE FROM account_lease WHERE account_id=? AND lease_owner=?", id, owner);
    store.db.update("DELETE FROM platform_account WHERE id=? AND user_id=?", id, user);
  }
}
