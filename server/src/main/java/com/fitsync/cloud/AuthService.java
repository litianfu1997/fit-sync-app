package com.fitsync.cloud;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final CloudStore store;
  private final CryptoService crypto;
  private final PasswordService passwords;
  private final String appId, appSecret;
  private final boolean devEnabled;
  private final long sessionDays;

  public AuthService(
      CloudStore store,
      CryptoService crypto,
      PasswordService passwords,
      Environment env,
      @Value("${fitsync.wechat-app-id:}") String appId,
      @Value("${fitsync.wechat-app-secret:}") String appSecret,
      @Value("${fitsync.dev-login-enabled:false}") boolean devEnabled,
      @Value("${fitsync.session-days:30}") long sessionDays) {
    this.store = store;
    this.crypto = crypto;
    this.passwords = passwords;
    this.appId = appId;
    this.appSecret = appSecret;
    this.sessionDays = sessionDays;
    boolean local =
        Arrays.asList(env.getActiveProfiles()).contains("local")
            || (env.getActiveProfiles().length == 0
                && Arrays.asList(env.getDefaultProfiles()).contains("local"));
    local = local && !Arrays.asList(env.getActiveProfiles()).contains("prod");
    if (devEnabled && !local) throw new IllegalStateException("开发登录只允许在 local 配置启用");
    this.devEnabled = devEnabled && local;
    if (Arrays.asList(env.getActiveProfiles()).contains("prod")
        && (appId.isBlank() || appSecret.isBlank()))
      throw new IllegalStateException("生产环境必须配置微信 AppID 和 AppSecret");
  }

  public boolean devEnabled() {
    return devEnabled;
  }

  public boolean wechatEnabled() {
    return !appId.isBlank() && !appSecret.isBlank();
  }

  /** 用户名密码注册登录对所有环境开放，不依赖微信或短信服务商配置。 */
  public boolean passwordLoginEnabled() {
    return true;
  }

  private static String validName(String raw, String fallback) {
    String name = raw == null ? "" : raw.strip();
    if (name.isEmpty()) name = fallback;
    if (name.length() > 80) throw ApiException.bad("昵称过长");
    return name;
  }

  /** 注册 FitSync 业务账号，成功后返回可直接使用的会话。 */
  @Transactional
  public Map<String, Object> register(String rawUsername, String rawPassword, String rawName) {
    String username = passwords.validUsername(rawUsername);
    passwords.validPassword(rawPassword, "密码");
    String name = validName(rawName, username);
    if (taken(username)) throw ApiException.bad("该用户名已被注册");
    String id = CloudStore.id();
    try {
      store.db.update(
          "INSERT INTO app_user(id,username,password_hash,display_name,created_at) VALUES(?,?,?,?,?)",
          id,
          username,
          passwords.hash(rawPassword),
          name,
          System.currentTimeMillis());
    } catch (DuplicateKeyException e) {
      throw ApiException.bad("该用户名已被注册");
    }
    return issue(id);
  }

  /** 用户名加密码登录；用户名不区分大小写。 */
  @Transactional
  public Map<String, Object> passwordLogin(String rawUsername, String rawPassword) {
    if (rawUsername == null || rawUsername.isBlank()) throw ApiException.bad("请输入用户名");
    if (rawPassword == null || rawPassword.isBlank()) throw ApiException.bad("请输入密码");
    String key = rawUsername.strip().toLowerCase(Locale.ROOT);
    if (key.length() > 64) throw ApiException.bad("账号或密码错误");
    var rows =
        store.db.queryForList(
            "SELECT id,password_hash FROM app_user WHERE username=?", key);
    String hash = rows.isEmpty() ? null : CloudStore.str(rows.getFirst(), "password_hash");
    if (hash == null || !passwords.matches(rawPassword, hash))
      throw ApiException.bad("账号或密码错误");
    return issue(CloudStore.str(rows.getFirst(), "id"));
  }

  private boolean taken(String username) {
    return store.db.queryForObject(
            "SELECT COUNT(*) FROM app_user WHERE username=?", Integer.class, username)
        > 0;
  }

  @Transactional
  public Map<String, Object> devLogin(String displayName) {
    if (!devEnabled) throw new ApiException(404, "NOT_FOUND", "开发登录未启用");
    String name = validName(displayName, "本地体验用户");
    String id = CloudStore.id();
    store.db.update(
        "INSERT INTO app_user(id,display_name,created_at) VALUES(?,?,?)",
        id,
        name,
        System.currentTimeMillis());
    return issue(id);
  }

  /** 微信一键登录；已配置时仅由小程序调用。先完成外部请求，再进入受锁保护的写入。 */
  public Map<String, Object> wechat(String code) {
    return loginOpenId(resolveOpenId(code));
  }

  /** 已登录用户在小程序内把当前账号绑定微信，使两端落到同一业务用户。 */
  public Map<String, Object> bindWechat(String user, String code) {
    return attachOpenId(user, resolveOpenId(code));
  }

  /** 与一键登录共用同一把锁，避免同一微信被并发绑定或创建两个用户。 */
  private synchronized Map<String, Object> attachOpenId(String user, String openId) {
    var owner = store.one("SELECT wechat_openid FROM app_user WHERE id=?", user);
    if (CloudStore.str(owner, "wechat_openid") != null)
      throw ApiException.bad("当前账号已绑定微信");
    var existing = store.db.queryForList("SELECT id FROM app_user WHERE wechat_openid=?", openId);
    if (!existing.isEmpty())
      throw ApiException.bad("该微信已绑定其他 FitSync 账号，请先登录该账号或在网页确认登录");
    if (store.db.update(
            "UPDATE app_user SET wechat_openid=? WHERE id=? AND wechat_openid IS NULL",
            openId,
            user)
        != 1) throw ApiException.bad("绑定失败，请刷新后重试");
    return account(user);
  }

  private String resolveOpenId(String code) {
    if (!wechatEnabled()) throw ApiException.bad("微信登录尚未配置");
    if (code == null || code.isBlank() || code.length() > 512) throw ApiException.bad("微信登录码无效");
    try {
      String url =
          "https://api.weixin.qq.com/sns/jscode2session?appid="
              + enc(appId)
              + "&secret="
              + enc(appSecret)
              + "&js_code="
              + enc(code)
              + "&grant_type=authorization_code";
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      var result = store.json.readTree(response.body());
      if (response.statusCode() != 200 || !result.hasNonNull("openid"))
        throw ApiException.bad("微信登录失败，请重新进入小程序后重试");
      return result.get("openid").asText();
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw ApiException.bad("微信登录服务暂不可用，请稍后重试");
    }
  }

  /** synchronized 保证同一 OpenID 并发登录不会创建两个用户；事务由公开入口开启。 */
  private synchronized Map<String, Object> loginOpenId(String openId) {
    var users = store.db.queryForList("SELECT id FROM app_user WHERE wechat_openid=?", openId);
    if (users.isEmpty()) {
      String id = CloudStore.id();
      store.db.update(
          "INSERT INTO app_user(id,wechat_openid,display_name,created_at) VALUES(?,?,?,?)",
          id,
          openId,
          "骑行用户",
          System.currentTimeMillis());
      return issue(id);
    }
    return issue(CloudStore.str(users.getFirst(), "id"));
  }

  /** 无密码用户补设用户名与密码；已有密码时请使用修改密码接口。 */
  @Transactional
  public Map<String, Object> setPassword(String user, String rawUsername, String rawPassword) {
    var row = store.one("SELECT username,password_hash FROM app_user WHERE id=?", user);
    if (CloudStore.str(row, "password_hash") != null)
      throw ApiException.bad("当前账号已设置密码，请使用修改密码");
    String username = passwords.validUsername(rawUsername);
    passwords.validPassword(rawPassword, "密码");
    if (taken(username)) throw ApiException.bad("该用户名已被注册");
    try {
      if (store.db.update(
              "UPDATE app_user SET username=?,password_hash=? WHERE id=? AND password_hash IS NULL",
              username,
              passwords.hash(rawPassword),
              user)
          != 1) throw ApiException.bad("密码设置失败，请刷新后重试");
    } catch (DuplicateKeyException e) {
      throw ApiException.bad("该用户名已被注册");
    }
    return account(user);
  }

  /** 修改密码必须验证旧密码；没有密码的账号走补设密码流程。 */
  @Transactional
  public Map<String, Object> changePassword(String user, String oldPassword, String newPassword) {
    var row = store.one("SELECT password_hash FROM app_user WHERE id=?", user);
    String hash = CloudStore.str(row, "password_hash");
    if (hash == null) throw ApiException.bad("当前账号尚未设置密码，请先补设密码");
    if (!passwords.matches(oldPassword, hash)) throw ApiException.bad("原密码不正确");
    passwords.validPassword(newPassword, "新密码");
    store.db.update("UPDATE app_user SET password_hash=? WHERE id=?", passwords.hash(newPassword), user);
    return Map.of("success", true);
  }

  /** 修改展示昵称，不影响用户名与登录；空值回退为默认昵称。 */
  @Transactional
  public Map<String, Object> updateProfile(String user, String rawName) {
    String name = validName(rawName, "骑行用户");
    store.db.update("UPDATE app_user SET display_name=? WHERE id=?", name, user);
    return account(user);
  }

  /** 用户详情包含账号绑定状态，供设置页决定是否显示绑定与补设密码入口。 */
  public Map<String, Object> account(String user) {
    return account(store.one("SELECT * FROM app_user WHERE id=?", user));
  }

  private Map<String, Object> account(Map<String, Object> row) {
    var result = new LinkedHashMap<String, Object>();
    result.put("id", row.get("id"));
    result.put("displayName", row.get("display_name"));
    result.put("hasPassword", row.get("password_hash") != null);
    result.put("hasWechat", row.get("wechat_openid") != null);
    String username = CloudStore.str(row, "username");
    if (username != null) result.put("username", username);
    return result;
  }

  private Map<String, Object> issue(String id) {
    String token = crypto.token();
    store.db.update(
        "INSERT INTO user_session(token_hash,user_id,expires_at) VALUES(?,?,?)",
        CryptoService.hash(token),
        id,
        System.currentTimeMillis() + Duration.ofDays(sessionDays).toMillis());
    return Map.of("token", token, "user", account(id));
  }

  @Transactional
  public Map<String, Object> createWebChallenge() {
    long now = System.currentTimeMillis(), expires = now + Duration.ofMinutes(5).toMillis();
    String id = CloudStore.id(), poll = crypto.token();
    String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    var random = new java.security.SecureRandom();
    var chars = new StringBuilder();
    for (int i = 0; i < 10; i++) chars.append(alphabet.charAt(random.nextInt(alphabet.length())));
    String code = chars.toString();
    store.db.update(
        "DELETE FROM web_login_challenge WHERE expires_at<?",
        now - Duration.ofMinutes(10).toMillis());
    store.db.update(
        "INSERT INTO web_login_challenge(id,code_hash,poll_hash,status,expires_at)"
            + " VALUES(?,?,?,'PENDING',?)",
        id,
        CryptoService.hash(code),
        CryptoService.hash(poll),
        expires);
    return Map.of("id", id, "code", code, "pollToken", poll, "expiresAtEpochMs", expires);
  }

  @Transactional
  public Map<String, Object> pollWebChallenge(String id, String poll) {
    if (poll == null || poll.length() > 128) throw ApiException.missing();
    var rows =
        store.db.queryForList(
            "SELECT * FROM web_login_challenge WHERE id=? AND poll_hash=?",
            id,
            CryptoService.hash(poll));
    if (rows.isEmpty()) throw ApiException.missing();
    var challenge = rows.getFirst();
    if (CloudStore.number(challenge, "expires_at") < System.currentTimeMillis())
      return Map.of("status", "EXPIRED");
    String status = CloudStore.str(challenge, "status");
    if (!status.equals("CONFIRMED")) return Map.of("status", status);
    if (store.db.update(
            "UPDATE web_login_challenge SET status='CONSUMED' WHERE id=? AND poll_hash=? AND"
                + " status='CONFIRMED' AND expires_at>?",
            id,
            CryptoService.hash(poll),
            System.currentTimeMillis())
        != 1) return Map.of("status", "CONSUMED");
    String user = CloudStore.str(challenge, "confirmed_user_id");
    var result = new LinkedHashMap<>(issue(user));
    result.put("status", "CONFIRMED");
    return result;
  }

  public Map<String, Object> confirmWebChallenge(String user, String code, boolean confirm) {
    if (!confirm) throw ApiException.bad("请明确确认将当前账号登录到网页");
    if (code == null || !code.strip().toUpperCase(Locale.ROOT).matches("[A-Z2-9]{10}"))
      throw ApiException.bad("请输入网页显示的 10 位登录码");
    int changed =
        store.db.update(
            "UPDATE web_login_challenge SET status='CONFIRMED',confirmed_user_id=? WHERE"
                + " code_hash=? AND status='PENDING' AND expires_at>?",
            user,
            CryptoService.hash(code.strip().toUpperCase(Locale.ROOT)),
            System.currentTimeMillis());
    if (changed != 1) throw ApiException.bad("登录码已过期或已被确认，请重新生成");
    return Map.of("confirmed", true);
  }

  /** 撤销当前 Bearer 会话；只影响这一个登录，其他设备上的会话保持有效。 */
  public void logout(String user, String header) {
    if (header == null || !header.startsWith("Bearer ") || header.length() > 512)
      throw new ApiException(401, "UNAUTHORIZED", "请先登录");
    store.db.update(
        "DELETE FROM user_session WHERE token_hash=? AND user_id=?",
        CryptoService.hash(header.substring(7)),
        user);
  }

  public String authenticate(String header) {
    if (header == null || !header.startsWith("Bearer ") || header.length() > 512)
      throw new ApiException(401, "UNAUTHORIZED", "请先登录");
    var rows =
        store.db.queryForList(
            "SELECT user_id FROM user_session WHERE token_hash=? AND expires_at>?",
            CryptoService.hash(header.substring(7)),
            System.currentTimeMillis());
    if (rows.isEmpty()) throw new ApiException(401, "UNAUTHORIZED", "登录已失效，请重新登录");
    return CloudStore.str(rows.getFirst(), "user_id");
  }

  private static String enc(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8);
  }
}
