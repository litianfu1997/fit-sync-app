package com.fitsync.cloud;

import com.fitsync.cloud.connector.ConnectorResult;
import com.fitsync.cloud.connector.Session;
import com.fitsync.cloud.connector.StravaConnector;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Strava 官方 OAuth 2.0 网页授权流程。
 *
 * <p>同时兼容 Web H5（授权后 302 跳回原页面）和微信小程序（授权页完成后轮询 challenge 状态），
 * 并用一次性高熵 state SHA-256 与 AES-256-GCM 加密上下文防止 CSRF 与重放。
 */
@Service
public class StravaOAuthService {
  public static final String CALLBACK_PATH = "/api/v1/accounts/oauth/strava/callback";
  private static final long TTL_MS = Duration.ofMinutes(10).toMillis();

  private final CloudStore store;
  private final CryptoService crypto;
  private final AccountService accounts;
  private final String globalClientId;
  private final String globalClientSecret;
  private final String configuredRedirectUri;
  private final Set<String> allowedOrigins;

  public StravaOAuthService(
      CloudStore store,
      CryptoService crypto,
      AccountService accounts,
      @Value("${fitsync.strava.client-id:}") String clientId,
      @Value("${fitsync.strava.client-secret:}") String clientSecret,
      @Value("${fitsync.strava.redirect-uri:}") String redirectUri,
      @Value("${fitsync.cors-origins:}") String corsOrigins) {
    this.store = store;
    this.crypto = crypto;
    this.accounts = accounts;
    this.globalClientId = clientId == null ? "" : clientId.strip();
    this.globalClientSecret = clientSecret == null ? "" : clientSecret.strip();
    this.configuredRedirectUri = redirectUri == null ? "" : redirectUri.strip();
    Set<String> origins = new LinkedHashSet<>();
    if (corsOrigins != null) {
      for (String item : corsOrigins.split(",")) {
        String s = item.strip();
        if (!s.isEmpty()) origins.add(s.toLowerCase(Locale.ROOT));
      }
    }
    this.allowedOrigins = Set.copyOf(origins);
  }

  public boolean isConfigured() {
    return !globalClientId.isEmpty() && !globalClientSecret.isEmpty();
  }

  public record CallbackOutcome(boolean ok, String message, String returnUrl) {}

  @Transactional
  public Map<String, Object> start(
      String user,
      String rawClientId,
      String rawClientSecret,
      String rawRedirectUri,
      String rawReturnUrl,
      HttpServletRequest request) {
    boolean useGlobal = isConfigured() && (rawClientId == null || rawClientId.isBlank());
    String clientId = useGlobal ? globalClientId : clean(rawClientId, 64);
    String clientSecret = useGlobal ? globalClientSecret : clean(rawClientSecret, 160);
    if (clientId == null || clientSecret == null) {
      throw ApiException.bad("请先配置或填写 Strava 应用的 Client ID 与客户端密钥");
    }

    String redirectUri = resolveRedirectUri(rawRedirectUri, request);
    String redirectOrigin = originOf(URI.create(redirectUri));
    String returnUrl = validateReturnUrl(rawReturnUrl, redirectOrigin);

    long now = System.currentTimeMillis();
    long expires = now + TTL_MS;
    String challengeId = CloudStore.id();
    String state = crypto.token();

    Map<String, Object> context = new LinkedHashMap<>();
    context.put("clientId", clientId);
    context.put("clientSecret", clientSecret);
    context.put("persistCredentials", !useGlobal);
    context.put("redirectUri", redirectUri);
    if (returnUrl != null) context.put("returnUrl", returnUrl);

    String encrypted = crypto.encrypt(store.encode(context), user + ":oauth:" + challengeId);
    store.db.update("DELETE FROM oauth_challenge WHERE expires_at<?", now - TTL_MS);
    store.db.update(
        "INSERT INTO oauth_challenge(id,user_id,platform,state_hash,status,encrypted_context,expires_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        challengeId,
        user,
        "STRAVA",
        CryptoService.hash(state),
        "PENDING",
        encrypted,
        expires);

    return Map.of(
        "challengeId",
        challengeId,
        "authorizeUrl",
        StravaConnector.authorizeUrl(clientId, redirectUri, state),
        "expiresAtEpochMs",
        expires);
  }

  @Transactional
  public CallbackOutcome completeCallback(
      String state, String code, String scope, String error) {
    if (state == null || state.isBlank() || state.length() > 128) {
      return new CallbackOutcome(false, "授权请求参数无效，请返回 FitSync 重新发起连接", null);
    }
    String stateHash = CryptoService.hash(state.strip());
    var rows = store.db.queryForList("SELECT * FROM oauth_challenge WHERE state_hash=?", stateHash);
    if (rows.isEmpty()) {
      return new CallbackOutcome(false, "授权请求不存在或已过期，请返回 FitSync 重新发起连接", null);
    }
    var row = rows.getFirst();
    String id = CloudStore.str(row, "id");
    String userId = CloudStore.str(row, "user_id");
    long now = System.currentTimeMillis();
    if (store.db.update(
            "UPDATE oauth_challenge SET status='CONSUMING' WHERE id=? AND state_hash=? AND"
                + " status='PENDING' AND expires_at>?",
            id,
            stateHash,
            now)
        != 1) {
      return new CallbackOutcome(false, "该 Strava 授权链接已处理或已过期，请重新发起连接", null);
    }

    Map<String, Object> context;
    try {
      context =
          store.decode(
              crypto.decrypt(CloudStore.str(row, "encrypted_context"), userId + ":oauth:" + id));
    } catch (RuntimeException e) {
      mark(id, "FAILED", "授权上下文解密失败，请重新发起");
      return new CallbackOutcome(false, "授权上下文失效，请返回 FitSync 重试", null);
    }
    String returnUrl = CloudStore.str(context, "returnUrl");

    if (error != null && !error.isBlank()) {
      String msg = "已取消 Strava 授权";
      mark(id, "FAILED", msg);
      return new CallbackOutcome(false, msg, returnUrl);
    }
    if (!StravaConnector.hasRequiredOAuthScopes(scope)) {
      String msg = "请在 Strava 授权页勾选全部活动读取与上传权限后重试";
      mark(id, "FAILED", msg);
      return new CallbackOutcome(false, msg, returnUrl);
    }

    String clientId = CloudStore.str(context, "clientId");
    String clientSecret = CloudStore.str(context, "clientSecret");
    boolean persistCredentials = Boolean.TRUE.equals(context.get("persistCredentials"));

    StravaConnector connector =
        accounts.connector("STRAVA") instanceof StravaConnector strava
            ? strava
            : new StravaConnector(globalClientId, globalClientSecret);
    ConnectorResult<Session> exchanged =
        connector.exchangeOAuthCode(clientId, clientSecret, code, scope, persistCredentials);
    if (exchanged.status() != ConnectorResult.Status.SUCCESS || exchanged.value() == null) {
      String msg = exchanged.message() == null ? "Strava 授权换取令牌失败" : exchanged.message();
      mark(id, "FAILED", msg);
      return new CallbackOutcome(false, msg, returnUrl);
    }

    Session session = exchanged.value();
    String displayName = session.attribute("displayName");
    try {
      accounts.persistOAuth(userId, "STRAVA", displayName, session);
    } catch (ApiException e) {
      mark(id, "FAILED", e.getMessage());
      return new CallbackOutcome(false, e.getMessage(), returnUrl);
    }

    mark(id, "SUCCEEDED", "Strava 已连接");
    return new CallbackOutcome(true, "Strava 授权成功，请返回 FitSync 查看连接状态", returnUrl);
  }

  public Map<String, Object> status(String user, String challengeId) {
    var rows =
        store.db.queryForList(
            "SELECT status,message,expires_at FROM oauth_challenge WHERE id=? AND user_id=?",
            challengeId,
            user);
    if (rows.isEmpty()) throw ApiException.missing();
    var row = rows.getFirst();
    String status = CloudStore.str(row, "status");
    long expiresAt = CloudStore.number(row, "expires_at");
    if ("PENDING".equals(status) && expiresAt < System.currentTimeMillis()) {
      status = "EXPIRED";
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("status", status);
    String message = CloudStore.str(row, "message");
    if (message != null && !message.isBlank()) result.put("message", message);
    return result;
  }

  private void mark(String id, String status, String message) {
    String clipped =
        message != null && message.length() > 280 ? message.substring(0, 280) : message;
    store.db.update(
        "UPDATE oauth_challenge SET status=?,message=? WHERE id=?", status, clipped, id);
  }

  private String resolveRedirectUri(String rawRedirectUri, HttpServletRequest request) {
    if (!configuredRedirectUri.isEmpty()) return configuredRedirectUri;
    String candidate =
        rawRedirectUri != null && !rawRedirectUri.isBlank()
            ? rawRedirectUri.strip()
            : defaultCallbackFromRequest(request);
    URI uri = parseHttpUri(candidate, "授权回调地址无效");
    if (!CALLBACK_PATH.equals(uri.getPath()) || uri.getRawQuery() != null || uri.getFragment() != null) {
      throw ApiException.bad("授权回调地址路径必须是 " + CALLBACK_PATH);
    }
    if (!"https".equalsIgnoreCase(uri.getScheme()) && !isLoopback(uri.getHost())) {
      throw ApiException.bad("授权回调地址必须使用 HTTPS；仅本地开发允许使用 HTTP");
    }
    return uri.toString();
  }

  private String defaultCallbackFromRequest(HttpServletRequest request) {
    String scheme = request.getScheme();
    String host = request.getServerName();
    int port = request.getServerPort();
    boolean standardPort =
        ("http".equalsIgnoreCase(scheme) && port == 80)
            || ("https".equalsIgnoreCase(scheme) && port == 443);
    return scheme + "://" + host + (standardPort ? "" : ":" + port) + CALLBACK_PATH;
  }

  private String validateReturnUrl(String rawReturnUrl, String redirectOrigin) {
    if (rawReturnUrl == null || rawReturnUrl.isBlank()) return null;
    String trimmed = rawReturnUrl.strip();
    if (trimmed.indexOf('\r') >= 0 || trimmed.indexOf('\n') >= 0) {
      throw ApiException.bad("返回地址格式无效");
    }
    URI uri = parseHttpUri(trimmed, "返回地址格式无效");
    if (!"https".equalsIgnoreCase(uri.getScheme()) && !isLoopback(uri.getHost())) {
      throw ApiException.bad("返回地址必须使用 HTTPS 或本地开发地址");
    }
    String origin = originOf(uri);
    boolean sameOrigin = origin.equalsIgnoreCase(redirectOrigin);
    boolean inCors = allowedOrigins.contains(origin.toLowerCase(Locale.ROOT));
    if (!sameOrigin && !inCors) {
      throw ApiException.bad("返回地址不在允许的域名白名单内");
    }
    return uri.toString();
  }

  private static URI parseHttpUri(String raw, String message) {
    try {
      URI uri = URI.create(raw);
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (scheme == null
          || host == null
          || uri.getUserInfo() != null
          || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
        throw ApiException.bad(message);
      }
      return uri;
    } catch (IllegalArgumentException e) {
      throw ApiException.bad(message);
    }
  }

  private static String originOf(URI uri) {
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    int port = uri.getPort();
    boolean standardPort =
        port == -1
            || ("http".equals(scheme) && port == 80)
            || ("https".equals(scheme) && port == 443);
    return scheme + "://" + host + (standardPort ? "" : ":" + port);
  }

  private static boolean isLoopback(String host) {
    return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
  }

  private static String clean(String value, int maxLen) {
    if (value == null) return null;
    String trimmed = value.strip();
    if (trimmed.isEmpty()
        || trimmed.length() > maxLen
        || trimmed.indexOf('\r') >= 0
        || trimmed.indexOf('\n') >= 0) return null;
    return trimmed;
  }
}
