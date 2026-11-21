package com.fitsync.cloud;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Google 官方 OAuth 2.0 授权码登录与绑定流程。
 *
 * <p>复用与 Strava 相同的安全边界：一次性高熵 state SHA-256 防 CSRF，AES-256-GCM 绑定上下文，
 * 登录成功后通过短期一次性 ticket 跳回前端交换 Bearer 会话，避免在重定向 URL 中暴露长期令牌。
 */
@Service
public class GoogleOAuthService {
  public static final String CALLBACK_PATH = "/api/v1/auth/google/callback";
  private static final long TTL_MS = Duration.ofMinutes(10).toMillis();
  private static final long TICKET_TTL_MS = Duration.ofMinutes(2).toMillis();

  @FunctionalInterface
  interface IdentityResolver {
    GoogleProfile resolve(String clientId, String clientSecret, String redirectUri, String code);
  }

  public record GoogleProfile(String sub, String email, boolean emailVerified, String name) {}

  public record CallbackOutcome(
      boolean ok, String mode, String message, String returnUrl, String ticket) {}

  private final CloudStore store;
  private final CryptoService crypto;
  private final AuthService auth;
  private final PasswordService passwords;
  private final String clientId;
  private final String clientSecret;
  private final String configuredRedirectUri;
  private final Set<String> allowedOrigins;
  private final IdentityResolver defaultResolver = this::fetchGoogleProfile;
  private volatile IdentityResolver identityResolver = defaultResolver;

  public GoogleOAuthService(
      CloudStore store,
      CryptoService crypto,
      AuthService auth,
      PasswordService passwords,
      @Value("${fitsync.google.client-id:}") String clientId,
      @Value("${fitsync.google.client-secret:}") String clientSecret,
      @Value("${fitsync.google.redirect-uri:}") String redirectUri,
      @Value("${fitsync.cors-origins:}") String corsOrigins) {
    this.store = store;
    this.crypto = crypto;
    this.auth = auth;
    this.passwords = passwords;
    this.clientId = clientId == null ? "" : clientId.strip();
    this.clientSecret = clientSecret == null ? "" : clientSecret.strip();
    this.configuredRedirectUri = redirectUri == null ? "" : redirectUri.strip();
    this.allowedOrigins = OAuthReturnUrls.parseAllowedOrigins(corsOrigins);
  }

  void setIdentityResolverForTest(IdentityResolver resolver) {
    this.identityResolver = resolver == null ? defaultResolver : resolver;
  }

  public boolean isConfigured() {
    return !clientId.isEmpty() && !clientSecret.isEmpty();
  }

  public Map<String, Object> startLogin(
      String rawRedirectUri, String rawReturnUrl, HttpServletRequest request) {
    return start(null, "LOGIN", rawRedirectUri, rawReturnUrl, request);
  }

  public Map<String, Object> startBind(
      String userId, String rawRedirectUri, String rawReturnUrl, HttpServletRequest request) {
    var owner = store.one("SELECT google_sub FROM app_user WHERE id=?", userId);
    if (CloudStore.str(owner, "google_sub") != null) {
      throw ApiException.bad("当前账号已绑定 Google 账号");
    }
    return start(userId, "BIND", rawRedirectUri, rawReturnUrl, request);
  }

  private Map<String, Object> start(
      String userId,
      String mode,
      String rawRedirectUri,
      String rawReturnUrl,
      HttpServletRequest request) {
    if (!isConfigured()) {
      throw ApiException.bad("Google 登录尚未配置");
    }
    String redirectUri = resolveRedirectUri(rawRedirectUri, request);
    String redirectOrigin = OAuthReturnUrls.originOf(URI.create(redirectUri));
    String returnUrl =
        OAuthReturnUrls.validateReturnUrl(rawReturnUrl, redirectOrigin, allowedOrigins);

    long now = System.currentTimeMillis();
    long expires = now + TTL_MS;
    String challengeId = CloudStore.id();
    String state = crypto.token();

    Map<String, Object> context = new LinkedHashMap<>();
    context.put("mode", mode);
    context.put("redirectUri", redirectUri);
    if (returnUrl != null) context.put("returnUrl", returnUrl);

    String aadOwner = (userId == null ? "anon" : userId) + ":google:" + challengeId;
    String encrypted = crypto.encrypt(store.encode(context), aadOwner);
    store.db.update("DELETE FROM google_oauth_challenge WHERE expires_at<?", now - TTL_MS);
    store.db.update(
        "INSERT INTO"
            + " google_oauth_challenge(id,user_id,state_hash,status,encrypted_context,expires_at)"
            + " VALUES(?,?,?,?,?,?)",
        challengeId,
        userId,
        CryptoService.hash(state),
        "PENDING",
        encrypted,
        expires);

    return Map.of(
        "challengeId",
        challengeId,
        "authorizeUrl",
        authorizeUrl(clientId, redirectUri, state),
        "expiresAtEpochMs",
        expires);
  }

  public CallbackOutcome completeCallback(String state, String code, String error) {
    if (state == null || state.isBlank() || state.length() > 128) {
      return new CallbackOutcome(false, "LOGIN", "Google 授权参数无效，请返回重试", null, null);
    }
    String stateHash = CryptoService.hash(state.strip());
    var rows =
        store.db.queryForList("SELECT * FROM google_oauth_challenge WHERE state_hash=?", stateHash);
    if (rows.isEmpty()) {
      return new CallbackOutcome(false, "LOGIN", "Google 授权请求不存在或已过期，请重新登录", null, null);
    }
    var row = rows.getFirst();
    String id = CloudStore.str(row, "id");
    String userId = CloudStore.str(row, "user_id");
    long now = System.currentTimeMillis();
    if (store.db.update(
            "UPDATE google_oauth_challenge SET status='CONSUMING' WHERE id=? AND state_hash=? AND"
                + " status='PENDING' AND expires_at>?",
            id,
            stateHash,
            now)
        != 1) {
      return new CallbackOutcome(false, "LOGIN", "该 Google 授权链接已失效，请重新发起", null, null);
    }

    Map<String, Object> context;
    try {
      String aadOwner = (userId == null ? "anon" : userId) + ":google:" + id;
      context = store.decode(crypto.decrypt(CloudStore.str(row, "encrypted_context"), aadOwner));
    } catch (RuntimeException e) {
      mark(id, "FAILED", "授权上下文失效，请重新发起");
      return new CallbackOutcome(false, "LOGIN", "授权上下文失效，请返回重试", null, null);
    }

    String mode = Objects.toString(context.get("mode"), "LOGIN");
    String redirectUri = CloudStore.str(context, "redirectUri");
    String returnUrl = CloudStore.str(context, "returnUrl");

    if (error != null && !error.isBlank()) {
      String msg = "已取消 Google 授权";
      mark(id, "FAILED", msg);
      return new CallbackOutcome(false, mode, msg, returnUrl, null);
    }
    if (code == null || code.isBlank() || code.length() > 2048) {
      String msg = "Google 未返回有效授权码，请重试";
      mark(id, "FAILED", msg);
      return new CallbackOutcome(false, mode, msg, returnUrl, null);
    }

    GoogleProfile profile;
    try {
      profile = identityResolver.resolve(clientId, clientSecret, redirectUri, code.strip());
    } catch (ApiException e) {
      mark(id, "FAILED", e.getMessage());
      return new CallbackOutcome(false, mode, e.getMessage(), returnUrl, null);
    }

    if (profile == null || profile.sub() == null || profile.sub().isBlank()) {
      String msg = "未获取到 Google 账号标识，请重试";
      mark(id, "FAILED", msg);
      return new CallbackOutcome(false, mode, msg, returnUrl, null);
    }

    String verifiedEmail = null;
    if (profile.emailVerified() && profile.email() != null && !profile.email().isBlank()) {
      try {
        verifiedEmail = passwords.validEmail(profile.email());
      } catch (ApiException ignored) {
        verifiedEmail = null;
      }
    }

    if ("BIND".equals(mode)) {
      if (userId == null || userId.isBlank()) {
        String msg = "绑定用户上下文丢失，请重新发起绑定";
        mark(id, "FAILED", msg);
        return new CallbackOutcome(false, mode, msg, returnUrl, null);
      }
      try {
        auth.bindGoogle(userId, profile.sub().strip(), verifiedEmail);
      } catch (ApiException e) {
        mark(id, "FAILED", e.getMessage());
        return new CallbackOutcome(false, mode, e.getMessage(), returnUrl, null);
      }
      mark(id, "SUCCEEDED", "Google 账号已绑定");
      return new CallbackOutcome(true, mode, "Google 账号已绑定，请返回 FitSync 查看", returnUrl, null);
    }

    String resolvedUserId;
    try {
      resolvedUserId =
          auth.resolveOrCreateGoogleUser(profile.sub().strip(), verifiedEmail, profile.name());
    } catch (ApiException e) {
      mark(id, "FAILED", e.getMessage());
      return new CallbackOutcome(false, mode, e.getMessage(), returnUrl, null);
    }

    String ticket = crypto.token();
    long ticketExpires = System.currentTimeMillis() + TICKET_TTL_MS;
    store.db.update(
        "UPDATE google_oauth_challenge SET"
            + " status='SUCCEEDED',ticket_hash=?,resolved_user_id=?,expires_at=?,message=? WHERE"
            + " id=?",
        CryptoService.hash(ticket),
        resolvedUserId,
        ticketExpires,
        "Google 登录成功",
        id);
    return new CallbackOutcome(true, "LOGIN", "Google 登录成功", returnUrl, ticket);
  }

  public Map<String, Object> exchangeTicket(String rawTicket) {
    if (rawTicket == null || rawTicket.isBlank() || rawTicket.length() > 256) {
      throw ApiException.bad("Google 登录凭证无效，请重新登录");
    }
    String ticketHash = CryptoService.hash(rawTicket.strip());
    long now = System.currentTimeMillis();
    var rows =
        store.db.queryForList(
            "SELECT id,resolved_user_id FROM google_oauth_challenge WHERE ticket_hash=? AND"
                + " status='SUCCEEDED' AND expires_at>?",
            ticketHash,
            now);
    if (rows.isEmpty()) {
      throw ApiException.bad("Google 登录凭证已过期或已使用，请重新登录");
    }
    var row = rows.getFirst();
    String id = CloudStore.str(row, "id");
    String resolvedUserId = CloudStore.str(row, "resolved_user_id");
    if (resolvedUserId == null
        || store.db.update(
                "UPDATE google_oauth_challenge SET status='EXCHANGED',ticket_hash=NULL WHERE id=?"
                    + " AND status='SUCCEEDED'",
                id)
            != 1) {
      throw ApiException.bad("Google 登录凭证已失效，请重新登录");
    }
    return auth.issueForUser(resolvedUserId);
  }

  private GoogleProfile fetchGoogleProfile(
      String cid, String csecret, String redirectUri, String code) {
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
      String form =
          "code="
              + enc(code)
              + "&client_id="
              + enc(cid)
              + "&client_secret="
              + enc(csecret)
              + "&redirect_uri="
              + enc(redirectUri)
              + "&grant_type=authorization_code";
      var tokenResp =
          client.send(
              HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                  .timeout(Duration.ofSeconds(15))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString(form))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      var tokenJson = store.json.readTree(tokenResp.body());
      String accessToken =
          tokenJson.hasNonNull("access_token") ? tokenJson.get("access_token").asText() : "";
      if (tokenResp.statusCode() != 200 || accessToken.isBlank()) {
        throw ApiException.bad("Google 授权换取令牌失败，请重试");
      }

      var userResp =
          client.send(
              HttpRequest.newBuilder(URI.create("https://openidconnect.googleapis.com/v1/userinfo"))
                  .timeout(Duration.ofSeconds(15))
                  .header("Authorization", "Bearer " + accessToken)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      var userJson = store.json.readTree(userResp.body());
      if (userResp.statusCode() != 200 || !userJson.hasNonNull("sub")) {
        throw ApiException.bad("获取 Google 账号信息失败，请重试");
      }
      String sub = userJson.get("sub").asText();
      String email = userJson.hasNonNull("email") ? userJson.get("email").asText() : null;
      boolean emailVerified =
          userJson.hasNonNull("email_verified") && userJson.get("email_verified").asBoolean();
      String name = userJson.hasNonNull("name") ? userJson.get("name").asText() : null;
      return new GoogleProfile(sub, email, emailVerified, name);
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw ApiException.bad("无法连接 Google 授权服务，请检查网络或服务器配置后重试");
    }
  }

  private void mark(String id, String status, String message) {
    String clipped =
        message != null && message.length() > 280 ? message.substring(0, 280) : message;
    store.db.update(
        "UPDATE google_oauth_challenge SET status=?,message=? WHERE id=?", status, clipped, id);
  }

  private static String authorizeUrl(String cid, String redirectUri, String state) {
    return "https://accounts.google.com/o/oauth2/v2/auth?client_id="
        + enc(cid)
        + "&redirect_uri="
        + enc(redirectUri)
        + "&response_type=code&scope="
        + enc("openid email profile")
        + "&state="
        + enc(state)
        + "&prompt=select_account";
  }

  private String resolveRedirectUri(String rawRedirectUri, HttpServletRequest request) {
    if (!configuredRedirectUri.isEmpty()) return configuredRedirectUri;
    String candidate =
        rawRedirectUri != null && !rawRedirectUri.isBlank()
            ? rawRedirectUri.strip()
            : OAuthReturnUrls.defaultCallbackFromRequest(request, CALLBACK_PATH);
    URI uri = OAuthReturnUrls.parseHttpUri(candidate, "Google 授权回调地址无效");
    if (!CALLBACK_PATH.equals(uri.getPath())
        || uri.getRawQuery() != null
        || uri.getFragment() != null) {
      throw ApiException.bad("Google 授权回调地址路径必须是 " + CALLBACK_PATH);
    }
    if (!"https".equalsIgnoreCase(uri.getScheme()) && !OAuthReturnUrls.isLoopback(uri.getHost())) {
      throw ApiException.bad("Google 授权回调地址必须使用 HTTPS；仅本地开发允许使用 HTTP");
    }
    return uri.toString();
  }

  private static String enc(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8);
  }
}
