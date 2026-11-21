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
  private final EmailService emailService;
  private final String appId, appSecret;
  private final boolean devEnabled;
  private final long sessionDays;

  public AuthService(
      CloudStore store,
      CryptoService crypto,
      PasswordService passwords,
      EmailService emailService,
      Environment env,
      @Value("${fitsync.wechat-app-id:}") String appId,
      @Value("${fitsync.wechat-app-secret:}") String appSecret,
      @Value("${fitsync.dev-login-enabled:false}") boolean devEnabled,
      @Value("${fitsync.session-days:30}") long sessionDays) {
    this.store = store;
    this.crypto = crypto;
    this.passwords = passwords;
    this.emailService = emailService;
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

  public boolean emailAuthEnabled() {
    return emailService.isConfigured();
  }

  private static String validName(String raw, String fallback) {
    String name = raw == null ? "" : raw.strip();
    if (name.isEmpty()) name = fallback;
    if (name.length() > 80) throw ApiException.bad("昵称过长");
    return name;
  }

  private static String defaultNameForEmail(String email) {
    int at = email.indexOf('@');
    String local = at > 0 ? email.substring(0, at) : email;
    if (local.isBlank()) return "骑行用户";
    return local.length() > 80 ? local.substring(0, 80) : local;
  }

  /** 发送邮箱验证码：REGISTER 验证邮箱未注册；LOGIN 验证邮箱已存在；BIND 校验已登录且未绑定邮箱。 */
  public Map<String, Object> sendEmailCode(
      String currentUserId, String rawEmail, String rawPurpose) {
    String email = passwords.validEmail(rawEmail);
    String purpose = emailService.normalizePurpose(rawPurpose);
    if ("REGISTER".equals(purpose)) {
      if (emailTaken(email)) throw ApiException.bad("该邮箱已被注册，请直接登录");
    } else if ("LOGIN".equals(purpose)) {
      if (!emailTaken(email)) throw ApiException.bad("该邮箱尚未注册，请先注册");
    } else if ("BIND".equals(purpose)) {
      if (currentUserId == null || currentUserId.isBlank()) {
        throw new ApiException(401, "UNAUTHORIZED", "请先登录");
      }
      var owner = store.one("SELECT email FROM app_user WHERE id=?", currentUserId);
      if (CloudStore.str(owner, "email") != null) throw ApiException.bad("当前账号已绑定邮箱");
      if (emailTaken(email)) throw ApiException.bad("该邮箱已被其他账号使用");
    }
    emailService.sendCode(email, purpose);
    return Map.of("success", true);
  }

  /** 通过邮箱验证码与密码注册 FitSync 账号；不加外层事务以免验证码错误计数随业务异常回滚。 */
  public Map<String, Object> registerWithEmail(
      String rawEmail, String rawCode, String rawPassword, String rawName) {
    String email = passwords.validEmail(rawEmail);
    passwords.validPassword(rawPassword, "密码");
    String name = validName(rawName, defaultNameForEmail(email));
    if (emailTaken(email)) throw ApiException.bad("该邮箱已被注册，请直接登录");
    emailService.verifyAndConsume(email, "REGISTER", rawCode);
    String id = CloudStore.id();
    try {
      store.db.update(
          "INSERT INTO app_user(id,email,password_hash,display_name,created_at) VALUES(?,?,?,?,?)",
          id,
          email,
          passwords.hash(rawPassword),
          name,
          System.currentTimeMillis());
    } catch (DuplicateKeyException e) {
      throw ApiException.bad("该邮箱已被注册，请直接登录");
    }
    return issue(id);
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

  /** 邮箱验证码登录；不加外层事务以免验证码错误计数随业务异常回滚。 */
  public Map<String, Object> emailLogin(String rawEmail, String rawCode) {
    String email = passwords.validEmail(rawEmail);
    var rows = store.db.queryForList("SELECT id FROM app_user WHERE email=?", email);
    if (rows.isEmpty()) throw ApiException.bad("该邮箱尚未注册，请先注册");
    emailService.verifyAndConsume(email, "LOGIN", rawCode);
    return issue(CloudStore.str(rows.getFirst(), "id"));
  }

  /** 邮箱或用户名加密码登录；不区分大小写。 */
  @Transactional
  public Map<String, Object> passwordLogin(String rawAccount, String rawPassword) {
    if (rawAccount == null || rawAccount.isBlank()) throw ApiException.bad("请输入邮箱或用户名");
    if (rawPassword == null || rawPassword.isBlank()) throw ApiException.bad("请输入密码");
    String key = rawAccount.strip().toLowerCase(Locale.ROOT);
    if (key.length() > 160) throw ApiException.bad("账号或密码错误");
    var rows =
        store.db.queryForList(
            "SELECT id,password_hash FROM app_user WHERE email=? OR username=?", key, key);
    if (rows.isEmpty()) throw ApiException.bad("账号或密码错误");
    String hash = CloudStore.str(rows.getFirst(), "password_hash");
    if (hash == null)
      throw ApiException.bad("该账号尚未设置密码，请使用邮箱验证码、Google 或微信登录后在设置页设置密码");
    if (!passwords.matches(rawPassword, hash)) throw ApiException.bad("账号或密码错误");
    return issue(CloudStore.str(rows.getFirst(), "id"));
  }

  private boolean taken(String username) {
    return store.db.queryForObject(
            "SELECT COUNT(*) FROM app_user WHERE username=?", Integer.class, username)
        > 0;
  }

  private boolean emailTaken(String email) {
    return store.db.queryForObject(
            "SELECT COUNT(*) FROM app_user WHERE email=?", Integer.class, email)
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
      throw ApiException.bad("该微信已绑定其他 FitSync 账号，请先登录该账号");
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

  /** 已登录用户验证邮箱验证码并绑定邮箱；不加外层事务以免验证码错误计数被回滚。 */
  public Map<String, Object> bindEmail(String user, String rawEmail, String rawCode) {
    String email = passwords.validEmail(rawEmail);
    var owner = store.one("SELECT email FROM app_user WHERE id=?", user);
    if (CloudStore.str(owner, "email") != null) throw ApiException.bad("当前账号已绑定邮箱");
    if (emailTaken(email)) throw ApiException.bad("该邮箱已被其他账号使用");
    emailService.verifyAndConsume(email, "BIND", rawCode);
    try {
      if (store.db.update(
              "UPDATE app_user SET email=? WHERE id=? AND email IS NULL", email, user)
          != 1) throw ApiException.bad("邮箱绑定失败，请刷新后重试");
    } catch (DuplicateKeyException e) {
      throw ApiException.bad("该邮箱已被其他账号使用");
    }
    return account(user);
  }

  /** 根据 Google OAuth 身份查找、同邮箱自动关联或创建业务用户。 */
  @Transactional
  public synchronized String resolveOrCreateGoogleUser(
      String sub, String verifiedEmail, String rawName) {
    var bySub = store.db.queryForList("SELECT id,email FROM app_user WHERE google_sub=?", sub);
    if (!bySub.isEmpty()) {
      var row = bySub.getFirst();
      String uid = CloudStore.str(row, "id");
      if (CloudStore.str(row, "email") == null
          && verifiedEmail != null
          && !emailTaken(verifiedEmail)) {
        store.db.update(
            "UPDATE app_user SET email=? WHERE id=? AND email IS NULL", verifiedEmail, uid);
      }
      return uid;
    }

    if (verifiedEmail != null) {
      var byEmail =
          store.db.queryForList("SELECT id,google_sub FROM app_user WHERE email=?", verifiedEmail);
      if (!byEmail.isEmpty()) {
        var row = byEmail.getFirst();
        if (CloudStore.str(row, "google_sub") != null) {
          throw ApiException.bad("该邮箱关联的账号已绑定其他 Google 账号");
        }
        String uid = CloudStore.str(row, "id");
        if (store.db.update(
                "UPDATE app_user SET google_sub=? WHERE id=? AND google_sub IS NULL", sub, uid)
            != 1) {
          throw ApiException.bad("关联 Google 账号失败，请重试");
        }
        return uid;
      }
    }

    String id = CloudStore.id();
    String emailToSet =
        (verifiedEmail != null && !emailTaken(verifiedEmail)) ? verifiedEmail : null;
    String fallback =
        emailToSet != null ? defaultNameForEmail(emailToSet) : "骑行用户";
    String name = validName(rawName, fallback);
    if (name.length() > 80) name = name.substring(0, 80);
    try {
      store.db.update(
          "INSERT INTO app_user(id,email,google_sub,display_name,created_at) VALUES(?,?,?,?,?)",
          id,
          emailToSet,
          sub,
          name,
          System.currentTimeMillis());
    } catch (DuplicateKeyException e) {
      throw ApiException.bad("Google 账号注册冲突，请重试");
    }
    return id;
  }

  /** 已登录用户绑定 Google 账号；若账号尚未设置邮箱则顺便补齐已验证邮箱。 */
  @Transactional
  public synchronized Map<String, Object> bindGoogle(
      String user, String sub, String verifiedEmail) {
    var owner = store.one("SELECT email,google_sub FROM app_user WHERE id=?", user);
    if (CloudStore.str(owner, "google_sub") != null)
      throw ApiException.bad("当前账号已绑定 Google 账号");
    var existing = store.db.queryForList("SELECT id FROM app_user WHERE google_sub=?", sub);
    if (!existing.isEmpty())
      throw ApiException.bad("该 Google 账号已绑定其他 FitSync 账号，请先登录该账号");
    try {
      if (store.db.update(
              "UPDATE app_user SET google_sub=? WHERE id=? AND google_sub IS NULL", sub, user)
          != 1) throw ApiException.bad("Google 账号绑定失败，请刷新后重试");
    } catch (DuplicateKeyException e) {
      throw ApiException.bad("该 Google 账号已绑定其他 FitSync 账号，请先登录该账号");
    }
    if (CloudStore.str(owner, "email") == null
        && verifiedEmail != null
        && !emailTaken(verifiedEmail)) {
      store.db.update(
          "UPDATE app_user SET email=? WHERE id=? AND email IS NULL", verifiedEmail, user);
    }
    return account(user);
  }

  @Transactional
  public Map<String, Object> issueForUser(String userId) {
    return issue(userId);
  }

  /** 无密码用户补设密码（已有邮箱时用户名可选填）；已有密码时请使用修改密码接口。 */
  @Transactional
  public Map<String, Object> setPassword(String user, String rawUsername, String rawPassword) {
    var row = store.one("SELECT username,email,password_hash FROM app_user WHERE id=?", user);
    if (CloudStore.str(row, "password_hash") != null)
      throw ApiException.bad("当前账号已设置密码，请使用修改密码");
    passwords.validPassword(rawPassword, "密码");
    String existingUsername = CloudStore.str(row, "username");
    boolean hasEmail = CloudStore.str(row, "email") != null;
    if ((rawUsername == null || rawUsername.isBlank())
        && (hasEmail || existingUsername != null)) {
      if (store.db.update(
              "UPDATE app_user SET password_hash=? WHERE id=? AND password_hash IS NULL",
              passwords.hash(rawPassword),
              user)
          != 1) throw ApiException.bad("密码设置失败，请刷新后重试");
      return account(user);
    }
    if (existingUsername != null) {
      String requested = passwords.validUsername(rawUsername);
      if (!existingUsername.equals(requested)) throw ApiException.bad("当前账号已绑定用户名，不可更改");
      if (store.db.update(
              "UPDATE app_user SET password_hash=? WHERE id=? AND password_hash IS NULL",
              passwords.hash(rawPassword),
              user)
          != 1) throw ApiException.bad("密码设置失败，请刷新后重试");
      return account(user);
    }
    String username = passwords.validUsername(rawUsername);
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
    String email = CloudStore.str(row, "email");
    result.put("hasEmail", email != null);
    if (email != null) result.put("email", email);
    result.put("hasGoogle", row.get("google_sub") != null);
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

  /** 撤销当前 Bearer 会话；只影响这一个登录，其他设备上的会话保持有效。 */
  public void logout(String user, String header) {
    if (header == null || !header.startsWith("Bearer ") || header.length() > 512)
      throw new ApiException(401, "UNAUTHORIZED", "请先登录");
    store.db.update(
        "DELETE FROM user_session WHERE token_hash=? AND user_id=?",
        CryptoService.hash(header.substring(7)),
        user);
  }

  /** 注销并彻底删除当前用户账号及其关联的活动、平台连接、同步规则、任务与会话。 */
  @Transactional
  public void deleteAccount(String user) {
    var rows = store.db.queryForList("SELECT id,email FROM app_user WHERE id=? FOR UPDATE", user);
    if (rows.isEmpty()) throw ApiException.missing();
    String email = CloudStore.str(rows.getFirst(), "email");

    store.db.update(
        "DELETE FROM account_lease WHERE account_id IN (SELECT id FROM platform_account WHERE"
            + " user_id=?)",
        user);
    store.db.update("DELETE FROM remote_activity_skip WHERE user_id=?", user);
    store.db.update("DELETE FROM sync_job WHERE user_id=?", user);
    store.db.update("DELETE FROM sync_rule WHERE user_id=?", user);
    store.db.update("DELETE FROM platform_copy WHERE user_id=?", user);
    store.db.update("DELETE FROM activity_variant WHERE user_id=?", user);
    store.db.update("DELETE FROM activity WHERE user_id=?", user);
    store.db.update("DELETE FROM oauth_challenge WHERE user_id=?", user);
    store.db.update(
        "DELETE FROM google_oauth_challenge WHERE user_id=? OR resolved_user_id=?", user, user);
    store.db.update("DELETE FROM platform_account WHERE user_id=?", user);
    store.db.update("DELETE FROM user_session WHERE user_id=?", user);
    if (email != null) {
      store.db.update("DELETE FROM email_verification_code WHERE email=?", email);
    }
    store.db.update("DELETE FROM app_user WHERE id=?", user);
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
