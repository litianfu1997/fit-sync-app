package com.fitsync.cloud.connector;

import static com.fitsync.cloud.connector.ConnectorResult.Status.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fitsync.fitcore.FitComposer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strava 官方 OAuth 2.0 授权、自动刷新、活动拉取与 FIT 上传；兼容历史网页会话。 */
public final class StravaConnector extends WebSessionConnector {
  public static final String REQUIRED_OAUTH_SCOPE = "read,activity:read_all,activity:write";
  private static final int PAGE_SIZE = 20;
  private static final int OAUTH_PAGE_SIZE = 100;
  private static final long REFRESH_EARLY_MS = 5 * 60 * 1000L;
  private static final String BASE = "https://www.strava.com";
  private static final Pattern UPLOAD_ID = Pattern.compile("[1-9]\\d*");
  private final String defaultClientId;
  private final String defaultClientSecret;

  public StravaConnector() {
    this("", "");
  }

  public StravaConnector(String defaultClientId, String defaultClientSecret) {
    this(defaultClientId, defaultClientSecret, defaultClient(), URI.create(BASE), Clock.systemUTC());
  }

  public StravaConnector(HttpClient client, URI apiBase, Clock clock) {
    this("", "", client, apiBase, clock);
  }

  public StravaConnector(
      String defaultClientId,
      String defaultClientSecret,
      HttpClient client,
      URI apiBase,
      Clock clock) {
    super(client, apiBase, BASE,
        Set.of(
            "www.strava.com",
            "strava.com",
            "d3nn82uahijw6k.cloudfront.net"), clock);
    this.defaultClientId = defaultClientId == null ? "" : defaultClientId.trim();
    this.defaultClientSecret = defaultClientSecret == null ? "" : defaultClientSecret.trim();
  }

  @Override
  public String platform() {
    return "STRAVA";
  }

  @Override
  void authenticate(HttpRequest.Builder request, Session session) {}

  @Override
  boolean businessSuccess(JsonNode root) {
    return true;
  }

  @Override
  String businessCode(JsonNode root) {
    return null;
  }

  @Override
  public ConnectorResult<Session> login(
      String username, String password, boolean storeCredentials) {
    if (blank(username) || blank(password))
      return fail(PERMANENT_FAILURE, "CREDENTIALS_REQUIRED", "请输入 Strava 邮箱和密码");
    String account = username.trim();
    URI loginUri = apiBase.resolve("/login");
    HttpRequest pageReq = HttpRequest.newBuilder(loginUri)
        .timeout(Duration.ofSeconds(60))
        .header("User-Agent", browserAgent(null))
        .header("Accept-Encoding", "gzip")
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .GET()
        .build();
    ConnectorResult<WireResponse> pageResult = sendWeb(pageReq, "Strava 登录页");
    if (pageResult.status() != SUCCESS) return pageResult.castFailure();
    WireResponse pageWire = pageResult.value();
    if (pageWire.status() == 429 || pageWire.status() >= 500)
      return statusFailure(pageWire.status(), "Strava 登录页");
    if (pageWire.status() < 200 || pageWire.status() >= 300)
      return fail(PERMANENT_FAILURE, "HTTP_" + pageWire.status(), "Strava 登录页不可用");
    String cookie = mergeCookies(null, pageWire.cookies());
    String html = new String(pageWire.bytes(), StandardCharsets.UTF_8);
    String csrf = extractCsrf(html);
    if (csrf == null) csrf = "";

    String form =
        "utf8=%E2%9C%93"
            + "&authenticity_token=" + q(csrf)
            + "&plan="
            + "&email=" + q(account)
            + "&password=" + q(password)
            + "&remember_me=on";
    HttpRequest.Builder postBuilder = HttpRequest.newBuilder(apiBase.resolve("/session"))
        .timeout(Duration.ofSeconds(60))
        .header("User-Agent", browserAgent(null))
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Origin", BASE)
        .header("Referer", loginUri.toString())
        .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
    if (!blank(cookie)) postBuilder.header("Cookie", cookie);
    ConnectorResult<WireResponse> postResult = sendWeb(postBuilder.build(), "Strava 登录提交");
    if (postResult.status() != SUCCESS) return postResult.castFailure();
    WireResponse postWire = postResult.value();
    cookie = mergeCookies(cookie, postWire.cookies());
    if (postWire.status() == 429 || postWire.status() >= 500)
      return statusFailure(postWire.status(), "Strava 登录");
    String location = postWire.header("Location");
    boolean returnedToLogin = location != null && location.toLowerCase(Locale.ROOT).contains("/login");
    boolean hasSessionCookie = !blank(cookie)
        && (cookie(cookie, "_strava4_session") != null
            || cookie(cookie, "strava_remember_token") != null
            || cookie.toLowerCase(Locale.ROOT).contains("session"));
    if (postWire.status() == 401
        || postWire.status() == 403
        || returnedToLogin
        || !hasSessionCookie) {
      return fail(
          PERMANENT_FAILURE,
          "STRAVA_AUTH_FAILED",
          "Strava 邮箱或密码错误，或触发了登录保护");
    }
    Map<String, String> values = new LinkedHashMap<>();
    values.put("cookie", cookie);
    values.put("userAgent", browserAgent(null));
    return ConnectorResult.success(
        new Session(null, null, account, password, storeCredentials, values));
  }

  /**
   * 授权完成后立即用访问令牌做一次只读校验。Strava 拒绝时把真实原因带回客户端，
   * 避免账号"刚连接就显示登录失效"却看不出是权限还是令牌问题。
   */
  public ConnectorResult<String> verifyOAuthAccess(Session session) {
    if (!isOAuth(session))
      return fail(PERMANENT_FAILURE, "STRAVA_OAUTH_REQUIRED", "该 Strava 账号不是 OAuth 连接");
    ConnectorResult<WireResponse> resp =
        sendOAuthGet(session, apiBase.resolve("/api/v3/athlete"), "Strava 账号校验");
    if (resp.status() != SUCCESS) return resp.castFailure();
    WireResponse wire = resp.value();
    if (wire.status() == 401 || wire.status() == 403)
      return oauthDenied(wire, "访问令牌校验").castFailure();
    if (wire.status() == 408 || wire.status() == 429 || wire.status() >= 500)
      return rateLimitOrServerFailure(wire, "Strava 账号校验");
    if (wire.status() < 200 || wire.status() >= 300)
      return fail(
          PERMANENT_FAILURE, "HTTP_" + wire.status(), "Strava 账号校验失败（HTTP " + wire.status() + "）");
    return ConnectorResult.success("ok");
  }

  /** 401/403 时把 Strava 返回的 message/errors 带进消息，避免只显示笼统的"登录已失效"。 */
  private static ConnectorResult<WireResponse> oauthDenied(WireResponse wire, String what) {
    String detail = oauthErrorDetail(wire);
    String message =
        "Strava 拒绝了" + what + "（HTTP " + wire.status() + "）"
            + (detail.isBlank() ? "，请重新连接并确认已勾选活动读取与上传权限" : "：" + detail);
    if (detail.toLowerCase(Locale.ROOT).contains("application status inactive"))
      message += "；请在 strava.com/settings/api 检查开发者应用状态与 API 订阅，应用恢复为 Active 后再连接";
    return fail(REQUIRES_LOGIN, "AUTH_EXPIRED", message);
  }

  private static String oauthErrorDetail(WireResponse wire) {
    try {
      JsonNode root = JSON.readTree(wire.bytes());
      String message = root.path("message").asText("").trim();
      List<String> parts = new ArrayList<>();
      for (JsonNode error : root.path("errors")) {
        String text =
            (error.path("resource").asText("").trim()
                    + " "
                    + error.path("field").asText("").trim()
                    + " "
                    + error.path("code").asText("").trim())
                .trim()
                .replaceAll("\\s+", " ");
        if (!text.isBlank()) parts.add(text);
      }
      String detail = parts.isEmpty() ? message : String.join("；", parts);
      return detail.length() > 160 ? detail.substring(0, 160) : detail;
    } catch (Exception e) {
      return "";
    }
  }

  public static boolean hasRequiredOAuthScopes(String scope) {
    if (blank(scope)) return false;
    Set<String> granted = new java.util.HashSet<>();
    for (String part : scope.split("[,\\s]+")) {
      if (!part.isBlank()) granted.add(part.trim().toLowerCase(Locale.ROOT));
    }
    return granted.contains("read")
        && granted.contains("activity:read_all")
        && granted.contains("activity:write");
  }

  public static String authorizeUrl(String clientId, String redirectUri, String state) {
    // 必须用 force：auto 在账号此前已授权过时会跳过授权页，用户没有机会补勾 activity:write，
    // 换到的令牌只能读不能传，表现为"刚连上就提示登录失效"。
    return BASE
        + "/oauth/authorize?client_id=" + q(clientId.trim())
        + "&redirect_uri=" + q(redirectUri.trim())
        + "&response_type=code&approval_prompt=force&scope=" + q(REQUIRED_OAUTH_SCOPE)
        + "&state=" + q(state);
  }

  /** 用 OAuth 授权码换取 access_token / refresh_token 与运动员资料。 */
  public ConnectorResult<Session> exchangeOAuthCode(
      String clientId,
      String clientSecret,
      String code,
      String grantedScope,
      boolean persistAppCredentials) {
    if (blank(clientId) || blank(clientSecret))
      return fail(PERMANENT_FAILURE, "STRAVA_APP_MISSING", "缺少 Strava Client ID 或 Client Secret");
    if (blank(code))
      return fail(PERMANENT_FAILURE, "STRAVA_CODE_MISSING", "Strava 授权回调缺少授权码");
    String cid = clientId.trim(), secret = clientSecret.trim();
    String form =
        "client_id=" + q(cid)
            + "&client_secret=" + q(secret)
            + "&code=" + q(code.trim())
            + "&grant_type=authorization_code";
    HttpRequest request = HttpRequest.newBuilder(apiBase.resolve("/oauth/token"))
        .timeout(Duration.ofSeconds(60))
        .header("Accept", "application/json")
        .header("Accept-Encoding", "gzip")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("User-Agent", USER_AGENT)
        .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
        .build();
    ConnectorResult<WireResponse> result = sendWeb(request, "Strava OAuth 令牌交换");
    if (result.status() != SUCCESS) return result.castFailure();
    WireResponse wire = result.value();
    if (wire.status() == 408 || wire.status() == 429 || wire.status() >= 500)
      return statusFailure(wire.status(), "Strava OAuth 令牌交换");
    if (wire.status() < 200 || wire.status() >= 300)
      return fail(PERMANENT_FAILURE, "STRAVA_OAUTH_FAILED", "Strava 授权码换取令牌失败（HTTP " + wire.status() + "）");
    try {
      JsonNode root = JSON.readTree(wire.bytes());
      String access = root.path("access_token").asText("").trim();
      String refresh = root.path("refresh_token").asText("").trim();
      long expiresAt = root.path("expires_at").asLong(0L);
      if (access.isBlank() || refresh.isBlank())
        return fail(PERMANENT_FAILURE, "STRAVA_OAUTH_FAILED", "Strava 未返回有效的访问令牌");
      // 令牌响应里的 scope 才是真正授予的范围（文档：可能少于请求值），回调参数仅作兜底。
      String tokenScope = root.path("scope").asText("").trim();
      String effectiveScope = tokenScope.isBlank() ? grantedScope : tokenScope;
      if (!hasRequiredOAuthScopes(effectiveScope))
        return fail(
            PERMANENT_FAILURE,
            "STRAVA_SCOPE_MISSING",
            "Strava 授权未包含上传权限（需要 " + REQUIRED_OAUTH_SCOPE + "），请在授权页勾选"
                + "“查看全部活动/上传活动”后重新连接；已授权过的账号需在 Strava 设置里撤销本应用后再授权");
      JsonNode athlete = root.path("athlete");
      long athleteNum = athlete.path("id").asLong(0L);
      String athleteId = athleteNum > 0 ? Long.toString(athleteNum) : null;
      String first = athlete.path("firstname").asText("").trim();
      String last = athlete.path("lastname").asText("").trim();
      String full = (first + " " + last).trim();
      if (full.isBlank()) full = athlete.path("username").asText("").trim();
      if (full.isBlank()) full = athleteId == null ? "Strava 账号" : "Strava · " + athleteId;

      Map<String, String> values = new LinkedHashMap<>();
      values.put("authType", "OAUTH");
      values.put("refreshToken", refresh);
      if (expiresAt > 0) values.put("expiresAt", Long.toString(expiresAt));
      if (!blank(effectiveScope)) values.put("scope", effectiveScope.trim());
      values.put("displayName", full);
      if (persistAppCredentials) {
        values.put("clientId", cid);
        values.put("clientSecret", secret);
      }
      return ConnectorResult.success(
          new Session(access, athleteId, null, null, false, values));
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "INVALID_RESPONSE", "Strava 令牌响应无法解析");
    }
  }

  private static boolean isOAuth(Session session) {
    return session != null
        && ("OAUTH".equals(session.attribute("authType"))
            || (!blank(session.accessToken()) && !blank(session.attribute("refreshToken"))));
  }

  private ConnectorResult<String> ensureOAuthToken(Session session, String failedToken) {
    synchronized (session) {
      String current = session.accessToken();
      String refresh = session.attribute("refreshToken");
      long expiresAt = parseLong(session.attribute("expiresAt"), 0L);
      boolean expiredOrSoon =
          expiresAt > 0 && expiresAt * 1000L - clock.millis() < REFRESH_EARLY_MS;
      boolean failedSame = failedToken != null && failedToken.equals(current);
      if (!blank(current) && !failedSame && !expiredOrSoon) {
        return ConnectorResult.success(current);
      }
      if (failedToken != null && !failedToken.equals(current) && !blank(current)) {
        return ConnectorResult.success(current);
      }
      String clientId = blank(session.attribute("clientId")) ? defaultClientId : session.attribute("clientId");
      String clientSecret =
          blank(session.attribute("clientSecret")) ? defaultClientSecret : session.attribute("clientSecret");
      if (blank(clientId) || blank(clientSecret) || blank(refresh)) {
        return needsLogin();
      }
      String form =
          "client_id=" + q(clientId)
              + "&client_secret=" + q(clientSecret)
              + "&refresh_token=" + q(refresh)
              + "&grant_type=refresh_token";
      HttpRequest request = HttpRequest.newBuilder(apiBase.resolve("/oauth/token"))
          .timeout(Duration.ofSeconds(60))
          .header("Accept", "application/json")
          .header("Accept-Encoding", "gzip")
          .header("Content-Type", "application/x-www-form-urlencoded")
          .header("User-Agent", USER_AGENT)
          .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
          .build();
      ConnectorResult<WireResponse> result = sendWeb(request, "Strava OAuth 刷新令牌");
      if (result.status() != SUCCESS) return result.castFailure();
      WireResponse wire = result.value();
      if (wire.status() == 400 || wire.status() == 401 || wire.status() == 403) {
        String detail = oauthErrorDetail(wire);
        return fail(
            REQUIRES_LOGIN,
            "AUTH_EXPIRED",
            "Strava 刷新访问令牌被拒绝（HTTP " + wire.status() + "）"
                + (detail.isBlank() ? "，请重新连接" : "：" + detail));
      }
      if (wire.status() == 408 || wire.status() == 429 || wire.status() >= 500)
        return statusFailure(wire.status(), "Strava 刷新令牌");
      if (wire.status() < 200 || wire.status() >= 300) return needsLogin();
      try {
        JsonNode root = JSON.readTree(wire.bytes());
        String nextAccess = root.path("access_token").asText("").trim();
        String nextRefresh = root.path("refresh_token").asText("").trim();
        long nextExpires = root.path("expires_at").asLong(0L);
        if (nextAccess.isBlank()) return needsLogin();
        // 先写刷新令牌与过期时间，最后写访问令牌：任一步落库时磁盘上都是一组可用凭据。
        if (!nextRefresh.isBlank()) session.setAttribute("refreshToken", nextRefresh);
        if (nextExpires > 0) session.setAttribute("expiresAt", Long.toString(nextExpires));
        session.setAccessToken(nextAccess);
        return ConnectorResult.success(nextAccess);
      } catch (Exception e) {
        return fail(RETRYABLE_FAILURE, "INVALID_RESPONSE", "Strava 刷新令牌响应无法解析");
      }
    }
  }

  private static long parseLong(String text, long fallback) {
    if (blank(text)) return fallback;
    try {
      return Long.parseLong(text.trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  @Override
  public ConnectorResult<Session> connectWeb(Map<String, String> input) {
    if (!validFields(input)) return invalidSession("网页会话字段无效或过长");
    String cookie = required(input, "cookie");
    if (cookie == null) return invalidSession("请填写 Strava 网页登录后的 Cookie");
    if (cookie(cookie, "_strava4_session") == null && !cookie.toLowerCase().contains("session"))
      return invalidSession("Cookie 中缺少 Strava 登录会话（_strava4_session）");
    Map<String, String> values = new LinkedHashMap<>();
    values.put("cookie", cookie);
    String agent = optional(input, "userAgent");
    if (agent != null) values.put("userAgent", agent);
    String csrf = optional(input, "csrf");
    if (csrf != null) values.put("csrf", csrf);
    Session session = new Session(null, optional(input, "externalUserId"), null, null, false, values);
    ConnectorResult<String> page = uploadPage(session);
    if (page.status() != SUCCESS) return page.castFailure();
    return ConnectorResult.success(session);
  }

  @Override
  public boolean skipPullAsDestination(Session session) {
    // OAuth 会话也能通过官方 Streams 接口重建 FIT 参与拉取，无需再跳过预拉取与定时拉取。
    return false;
  }

  @Override
  public ConnectorResult<ActivityPage> listActivities(
      Session session, long sinceEpochMs, String cursor) {
    if (isOAuth(session)) {
      return listActivitiesOAuth(session, sinceEpochMs, cursor);
    }
    ConnectorResult<ActivityPage> first = listActivitiesOnce(session, sinceEpochMs, cursor);
    if (first.status() == REQUIRES_LOGIN && reloginIfPossible(session)) {
      return listActivitiesOnce(session, sinceEpochMs, cursor);
    }
    return first;
  }

  private ConnectorResult<ActivityPage> listActivitiesOAuth(
      Session session, long sinceEpochMs, String cursor) {
    int page = 1;
    long beforeSec = clock.millis() / 1000L;
    if (!blank(cursor)) {
      String[] parts = cursor.split(":", 2);
      page = pageNumber(parts[0]);
      if (parts.length == 2) beforeSec = parseLong(parts[1], beforeSec);
    }
    long afterSec = Math.max(0L, sinceEpochMs / 1000L);
    URI uri = apiBase.resolve(
        "/api/v3/athlete/activities?after=" + afterSec
            + "&before=" + beforeSec
            + "&page=" + page
            + "&per_page=" + OAUTH_PAGE_SIZE);
    ConnectorResult<WireResponse> resp = sendOAuthGet(session, uri, "Strava 活动列表");
    if (resp.status() != SUCCESS) return resp.castFailure();
    WireResponse wire = resp.value();
    if (wire.status() == 401 || wire.status() == 403) return oauthDenied(wire, "活动列表请求").castFailure();
    if (wire.status() == 408 || wire.status() == 429 || wire.status() >= 500)
      return rateLimitOrServerFailure(wire, "Strava 活动列表");
    if (wire.status() < 200 || wire.status() >= 300)
      return fail(PERMANENT_FAILURE, "HTTP_" + wire.status(), "Strava 活动列表请求失败");
    try {
      JsonNode array = JSON.readTree(wire.bytes());
      if (array == null || !array.isArray())
        return fail(RETRYABLE_FAILURE, "INVALID_ACTIVITY_LIST", "Strava 活动列表响应格式异常");
      List<RemoteActivity> items = new ArrayList<>();
      boolean reachedWindowEnd = false;
      for (JsonNode item : array) {
        String id = positive(item, "id_str");
        if (id == null) id = positive(item, "id");
        if (id == null) continue;
        Long start = oauthStart(item);
        if (start != null && start < sinceEpochMs) {
          reachedWindowEnd = true;
          continue;
        }
        String activityUrl = BASE + "/activities/" + id;
        String sport = item.path("sport_type").asText("").trim();
        if (sport.isBlank()) sport = item.path("type").asText("").trim();
        double distance = item.path("distance").asDouble(Double.NaN);
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("remote_url", activityUrl);
        meta.put("sport_type", sport);
        String externalId = item.path("external_id").asText("").trim();
        if (!externalId.isBlank()) meta.put("external_id", externalId);
        items.add(new RemoteActivity(
            id,
            start,
            item.path("name").asText("").trim(),
            Double.isNaN(distance) ? null : distance,
            activityUrl + "/export_original",
            meta));
      }
      boolean hasMore = !reachedWindowEnd && array.size() >= OAUTH_PAGE_SIZE;
      String nextCursor = hasMore ? (page + 1) + ":" + beforeSec : null;
      return ConnectorResult.success(new ActivityPage(items, nextCursor, hasMore));
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "INVALID_ACTIVITY_LIST", "Strava 活动列表响应无法解析");
    }
  }

  private static Long oauthStart(JsonNode item) {
    String raw = item.path("start_date").asText("").trim();
    if (raw.isBlank()) return null;
    try {
      return java.time.Instant.parse(raw).toEpochMilli();
    } catch (RuntimeException ignored) {
      try {
        return java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli();
      } catch (RuntimeException ignored2) {
        return null;
      }
    }
  }

  private ConnectorResult<WireResponse> sendOAuthGet(Session session, URI uri, String what) {
    ConnectorResult<String> tokenRes = ensureOAuthToken(session, null);
    if (tokenRes.status() != SUCCESS) return tokenRes.castFailure();
    String token = tokenRes.value();
    HttpRequest req = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(60))
        .header("Authorization", "Bearer " + token)
        .header("Accept", "application/json")
        .header("Accept-Encoding", "gzip")
        .header("User-Agent", USER_AGENT)
        .GET().build();
    ConnectorResult<WireResponse> first = sendWeb(req, what);
    if (first.status() != SUCCESS) return first;
    if (first.value().status() != 401) return first;
    ConnectorResult<String> refreshed = ensureOAuthToken(session, token);
    if (refreshed.status() != SUCCESS) return refreshed.castFailure();
    HttpRequest retry = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(60))
        .header("Authorization", "Bearer " + refreshed.value())
        .header("Accept", "application/json")
        .header("Accept-Encoding", "gzip")
        .header("User-Agent", USER_AGENT)
        .GET().build();
    return sendWeb(retry, what);
  }

  private <T> ConnectorResult<T> rateLimitOrServerFailure(WireResponse wire, String what) {
    if (wire.status() == 429) {
      long retryMs = 900_000L;
      String retryAfter = wire.header("Retry-After");
      if (retryAfter != null) {
        try {
          long sec = Long.parseLong(retryAfter.trim());
          if (sec > 0) retryMs = Math.max(sec * 1000L, 60_000L);
        } catch (NumberFormatException ignored) {
        }
      }
      return ConnectorResult.failure(
          RETRYABLE_FAILURE,
          "RATE_LIMITED",
          what + "触发 Strava API 频率限制，已自动延后重试",
          retryMs);
    }
    return statusFailure(wire.status(), what);
  }

  private ConnectorResult<ActivityPage> listActivitiesOnce(
      Session session, long sinceEpochMs, String cursor) {
    String cookie = cookie(session);
    if (cookie == null) return needsLogin();
    int page = pageNumber(cursor);
    URI uri = apiBase.resolve(
        "/athlete/training_activities?keywords=&sport_type=&tags=&commute=&private_activities=&trainer=&gear=&new_activity_only=false&page="
            + page + "&per_page=" + PAGE_SIZE);
    HttpRequest request = webRequest(uri, session, cookie)
        .header("Accept", "text/javascript, application/javascript, application/ecmascript")
        .header("X-Requested-With", "XMLHttpRequest")
        .GET().build();
    ConnectorResult<WireResponse> response = sendWeb(request, "Strava 活动列表");
    if (response.status() != SUCCESS) return response.castFailure();
    captureCookies(session, response.value());
    WireResponse wire = response.value();
    if (wire.status() == 401 || wire.status() == 403) return needsLogin();
    if (wire.status() == 408 || wire.status() == 429 || wire.status() >= 500)
      return statusFailure(wire.status(), "Strava 活动列表");
    try {
      JsonNode root = JSON.readTree(wire.bytes());
      JsonNode models = root.path("models");
      if (!models.isArray()) return fail(PERMANENT_FAILURE, "STRAVA_PAGE_CHANGED", "Strava 网页活动响应缺少 models");
      int receivedPage = Math.max(1, root.path("page").asInt(page));
      int perPage = Math.max(1, root.path("perPage").asInt(PAGE_SIZE));
      int total = Math.max(0, root.path("total").asInt(models.size()));
      boolean reachedWindowEnd = false;
      List<RemoteActivity> items = new ArrayList<>();
      for (JsonNode item : models) {
        String id = positive(item, "id_str");
        if (id == null) id = positive(item, "id");
        if (id == null) continue;
        Long start = stravaStart(item);
        if (start != null && start < sinceEpochMs) {
          reachedWindowEnd = true;
          continue;
        }
        String activityUrl = item.path("activity_url").asText("").trim();
        if (!activityUrl.startsWith(BASE + "/activities/")) activityUrl = BASE + "/activities/" + id;
        String sport = item.path("sport_type").asText("").trim();
        double distance = item.path("distance_raw").asDouble(Double.NaN);
        items.add(new RemoteActivity(id, start, item.path("name").asText("").trim(),
            Double.isNaN(distance) ? null : distance, activityUrl + "/export_original",
            Map.of("remote_url", activityUrl, "sport_type", sport)));
      }
      boolean hasMore = !reachedWindowEnd && models.size() > 0 && receivedPage * perPage < total;
      return ConnectorResult.success(
          new ActivityPage(items, hasMore ? Integer.toString(receivedPage + 1) : null, hasMore));
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "INVALID_ACTIVITY_LIST", "Strava 网页活动响应无法解析");
    }
  }

  private static Long stravaStart(JsonNode item) {
    String raw = item.path("start_time").asText("").trim();
    if (!raw.isBlank()) {
      try {
        return java.time.OffsetDateTime.parse(
            raw, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"))
            .toInstant().toEpochMilli();
      } catch (RuntimeException ignored) {
      }
    }
    long epoch = item.path("start_date_local_raw").asLong(0);
    return epoch > 0 ? epoch * 1000 : null;
  }

  @Override
  public ConnectorResult<FitDownload> downloadFit(Session session, RemoteActivity activity) {
    if (isOAuth(session) && blank(cookie(session))) {
      return rebuildFitFromStreams(session, activity);
    }
    ConnectorResult<FitDownload> first = downloadFitOnce(session, activity);
    if (first.status() == REQUIRES_LOGIN && reloginIfPossible(session)) {
      return downloadFitOnce(session, activity);
    }
    return first;
  }

  /**
   * 纯 OAuth 会话拿不到网页版 export_original 的原始文件，改为读取官方 Streams 曲线
   * （时间/轨迹/心率/功率/踏频/海拔）在服务端重建 FIT。每活动只消耗一次读配额。
   */
  private ConnectorResult<FitDownload> rebuildFitFromStreams(
      Session session, RemoteActivity activity) {
    Long start = activity.startTimeEpochMs();
    if (start == null)
      return fail(PERMANENT_FAILURE, "NO_ORIGINAL_FILE", "该 Strava 活动缺少开始时间，无法重建 FIT");
    URI uri = apiBase.resolve(
        "/api/v3/activities/" + activity.remoteActivityId()
            + "/streams?keys=time,latlng,distance,altitude,velocity_smooth,heartrate,cadence,watts,moving"
            + "&key_by_type=true");
    ConnectorResult<WireResponse> resp = sendOAuthGet(session, uri, "Strava 活动曲线");
    if (resp.status() != SUCCESS) return resp.castFailure();
    WireResponse wire = resp.value();
    if (wire.status() == 401 || wire.status() == 403) return oauthDenied(wire, "活动曲线请求").castFailure();
    if (wire.status() == 404 || wire.status() == 410)
      return fail(PERMANENT_FAILURE, "NO_ORIGINAL_FILE", "该 Strava 活动没有可用的曲线数据");
    if (wire.status() == 408 || wire.status() == 429 || wire.status() >= 500)
      return rateLimitOrServerFailure(wire, "Strava 活动曲线");
    if (wire.status() < 200 || wire.status() >= 300)
      return fail(PERMANENT_FAILURE, "HTTP_" + wire.status(), "Strava 活动曲线请求失败");
    try {
      JsonNode root = JSON.readTree(wire.bytes());
      FitComposer.Streams streams = new FitComposer.Streams();
      streams.time = intSeries(root, "time");
      if (streams.time == null)
        return fail(PERMANENT_FAILURE, "NO_ORIGINAL_FILE", "该 Strava 活动没有可用的时间曲线");
      streams.latlng = latlngSeries(root);
      streams.distance = doubleSeries(root, "distance");
      streams.altitude = doubleSeries(root, "altitude");
      streams.speed = doubleSeries(root, "velocity_smooth");
      streams.heartRate = intSeries(root, "heartrate");
      streams.cadence = intSeries(root, "cadence");
      streams.power = intSeries(root, "watts");
      streams.moving = movingSeries(root);
      byte[] fit = FitComposer.compose(start, activity.metadata().get("sport_type"), streams);
      return ConnectorResult.success(
          new FitDownload(fit, "strava_streams_" + activity.remoteActivityId() + ".fit"));
    } catch (IllegalArgumentException invalid) {
      return fail(PERMANENT_FAILURE, "NO_ORIGINAL_FILE", "该 Strava 活动曲线无效，无法重建 FIT");
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "INVALID_RESPONSE", "Strava 活动曲线响应无法解析");
    }
  }

  /** Streams 接口对每条曲线可能直接返回数组，也可能包一层 {data:[...]}，两种都要认。 */
  private static JsonNode seriesData(JsonNode root, String key) {
    JsonNode node = root.get(key);
    if (node == null || node.isNull()) return null;
    if (node.isObject()) node = node.get("data");
    if (node == null || !node.isArray() || node.isEmpty()) return null;
    return node;
  }

  private static int[] intSeries(JsonNode root, String key) {
    JsonNode array = seriesData(root, key);
    if (array == null) return null;
    int[] values = new int[array.size()];
    for (int i = 0; i < values.length; i++) {
      JsonNode item = array.get(i);
      values[i] = item == null || !item.isNumber() ? -1 : (int) Math.round(item.asDouble());
    }
    return values;
  }

  private static double[] doubleSeries(JsonNode root, String key) {
    JsonNode array = seriesData(root, key);
    if (array == null) return null;
    double[] values = new double[array.size()];
    for (int i = 0; i < values.length; i++) {
      JsonNode item = array.get(i);
      values[i] = item == null || !item.isNumber() ? Double.NaN : item.asDouble();
    }
    return values;
  }

  private static double[][] latlngSeries(JsonNode root) {
    JsonNode array = seriesData(root, "latlng");
    if (array == null) return null;
    double[][] values = new double[array.size()][];
    for (int i = 0; i < values.length; i++) {
      JsonNode pair = array.get(i);
      if (pair == null || !pair.isArray() || pair.size() < 2
          || !pair.get(0).isNumber() || !pair.get(1).isNumber()) {
        continue;
      }
      values[i] = new double[] {pair.get(0).asDouble(), pair.get(1).asDouble()};
    }
    return values;
  }

  private static boolean[] movingSeries(JsonNode root) {
    JsonNode array = seriesData(root, "moving");
    if (array == null) return null;
    boolean[] values = new boolean[array.size()];
    for (int i = 0; i < values.length; i++) values[i] = array.get(i) != null && array.get(i).asBoolean(false);
    return values;
  }

  private ConnectorResult<FitDownload> downloadFitOnce(Session session, RemoteActivity activity) {
    String cookie = cookie(session);
    if (cookie == null) return needsLogin();
    String current = activity.downloadUrl();
    if (current == null || current.isBlank())
      current = BASE + "/activities/" + activity.remoteActivityId() + "/export_original";
    for (int redirect = 0; redirect <= 3; redirect++) {
      URI uri;
      try {
        uri = URI.create(current);
      } catch (RuntimeException e) {
        return fail(PERMANENT_FAILURE, "INVALID_URL", "Strava 原文件地址无效");
      }
      boolean stravaHost = uri.getHost() != null
          && (uri.getHost().equalsIgnoreCase("www.strava.com")
              || uri.getHost().equalsIgnoreCase("strava.com"));
      if (!httpsHost(current, Set.of("www.strava.com", "strava.com",
          "d3nn82uahijw6k.cloudfront.net")))
        return fail(PERMANENT_FAILURE, "UNTRUSTED_FIT_HOST", "Strava 原文件地址不在允许的 HTTPS 域名内");
      HttpRequest request = HttpRequest.newBuilder(uri)
          .header("Accept-Encoding", "identity")
          .header("Accept", "application/octet-stream,*/*")
          .header("User-Agent", browserAgent(session))
          .timeout(java.time.Duration.ofSeconds(90))
          .GET().build();
      if (stravaHost) request = request.newBuilder().header("Cookie", cookie).build();
      ConnectorResult<WireResponse> response = sendWeb(request, "Strava 原文件下载");
      if (response.status() != SUCCESS) return response.castFailure();
      WireResponse wire = response.value();
      if (stravaHost) captureCookies(session, wire);
      if (wire.status() >= 300 && wire.status() < 400) {
        String location = wire.header("Location");
        if (location != null && location.toLowerCase().contains("login")) return needsLogin();
        URI next;
        try {
          next = uri.resolve(location == null ? "" : location);
        } catch (RuntimeException e) {
          return fail(PERMANENT_FAILURE, "INVALID_REDIRECT", "Strava 原文件跳转地址无效");
        }
        if (!httpsHost(next.toString(), Set.of("www.strava.com", "strava.com",
            "d3nn82uahijw6k.cloudfront.net")))
          return fail(PERMANENT_FAILURE, "INSECURE_REDIRECT", "Strava 原文件跳转到非白名单地址");
        current = next.toString();
        continue;
      }
      if (wire.status() == 401 || wire.status() == 403) return needsLogin();
      if (wire.status() == 404 || wire.status() == 410)
        return fail(PERMANENT_FAILURE, "NO_ORIGINAL_FILE", "该 Strava 活动没有可导出的原始文件");
      if (wire.status() == 408 || wire.status() == 429 || wire.status() >= 500)
        return statusFailure(wire.status(), "Strava 原文件");
      if (wire.status() < 200 || wire.status() >= 300)
        return fail(PERMANENT_FAILURE, "HTTP_" + wire.status(), "Strava 原文件下载失败");
      try {
        byte[] bytes = wire.bytes();
        if (bytes.length >= 2 && bytes[0] == 0x1f && bytes[1] == (byte) 0x8b) {
          try (var gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
            bytes = readBounded(gzip, MAX_FIT_BYTES);
          }
        }
        byte[] fit = extractFit(bytes);
        if (fit == null) {
          String text = new String(bytes, 0, Math.min(bytes.length, 64), java.nio.charset.StandardCharsets.UTF_8)
              .trim().toLowerCase();
          if (text.startsWith("<html") || text.startsWith("<!doctype") || text.startsWith("{"))
            return needsLogin();
          return fail(PERMANENT_FAILURE, "UNSUPPORTED_ORIGINAL_FORMAT",
              "该活动的原始文件不是 FIT，暂不自动导入");
        }
        return ConnectorResult.success(
            new FitDownload(fit, "strava_" + activity.remoteActivityId() + ".fit"));
      } catch (Exception e) {
        return fail(PERMANENT_FAILURE, "INVALID_ORIGINAL_FILE", "Strava 原文件损坏或超过大小限制");
      }
    }
    return fail(RETRYABLE_FAILURE, "REDIRECT", "Strava 原文件跳转次数过多");
  }

  private ConnectorResult<String> uploadPage(Session session) {
    ConnectorResult<String> first = uploadPageOnce(session);
    if (first.status() == REQUIRES_LOGIN && reloginIfPossible(session)) {
      return uploadPageOnce(session);
    }
    return first;
  }

  private ConnectorResult<String> uploadPageOnce(Session session) {
    String cached = session == null ? null : session.attribute("csrf");
    if (cached != null) return ConnectorResult.success(cached);
    String cookie = cookie(session);
    if (cookie == null) return needsLogin();
    HttpRequest request = webRequest(apiBase.resolve("/upload/select"), session, cookie)
        .header("Accept", "text/html,application/xhtml+xml")
        .GET().build();
    ConnectorResult<WireResponse> response = sendWeb(request, "Strava 上传页");
    if (response.status() != SUCCESS) return response.castFailure();
    WireResponse wire = response.value();
    captureCookies(session, wire);
    if (wire.status() == 401 || wire.status() == 403) return needsLogin();
    if (wire.status() >= 300 && wire.status() < 400) {
      if (wire.header("Location") != null && wire.header("Location").toLowerCase().contains("login"))
        return needsLogin();
      return fail(RETRYABLE_FAILURE, "UNEXPECTED_REDIRECT", "Strava 上传页发生未预期跳转");
    }
    if (!(wire.status() >= 200 && wire.status() < 300))
      return statusFailure(wire.status(), "Strava 上传页");
    String html = new String(wire.bytes(), java.nio.charset.StandardCharsets.UTF_8);
    String token = extractCsrf(html);
    if (token == null) {
      if (html.toLowerCase().contains("name=\"email\"") || html.toLowerCase().contains("name='email'"))
        return needsLogin();
      return fail(PERMANENT_FAILURE, "STRAVA_PAGE_CHANGED", "Strava 上传页结构已变化");
    }
    session.setAttribute("csrf", token);
    return ConnectorResult.success(token);
  }

  @Override
  public ConnectorResult<UploadResult> uploadFit(Session session, byte[] fitBytes, String fileName) {
    if (isOAuth(session)) {
      return uploadFitOAuth(session, fitBytes, fileName);
    }
    ConnectorResult<Void> valid = validateUpload(fitBytes);
    if (valid.status() != SUCCESS) return valid.castFailure();
    String cookie = cookie(session);
    if (cookie == null) return needsLogin();
    ConnectorResult<String> page = uploadPage(session);
    if (page.status() != SUCCESS) return page.castFailure();
    String csrf = page.value(), boundary = "----WebKitFormBoundary" + hash("SHA-256", fitBytes).substring(0, 24);
    byte[] body;
    try {
      body = multipart(boundary,
          List.of(
              Part.field("_method", "post"),
              Part.field("authenticity_token", csrf),
              Part.file("files[]", safeFileName(fileName), fitBytes)));
    } catch (Exception e) {
      return fail(PERMANENT_FAILURE, "UPLOAD_PREPARE_FAILED", "Strava 上传内容准备失败");
    }
    HttpRequest request = webRequest(apiBase.resolve("/upload/files"), session, cookie)
        .header("Accept", "application/json, text/javascript, */*; q=0.01")
        .header("X-CSRF-Token", csrf)
        .header("X-Requested-With", "XMLHttpRequest")
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    ConnectorResult<WireResponse> response = sendWeb(request, "Strava 上传");
    if (response.status() != SUCCESS) return uncertainUpload(fitBytes, null);
    captureCookies(session, response.value());
    ConnectorResult<UploadResult> result = parseUploadResponse(response.value(), null, fitBytes);
    if (result.status() == RETRYABLE_FAILURE || result.status() == REQUIRES_USER_ACTION)
      return uncertainUpload(fitBytes, null);
    return result;
  }

  private ConnectorResult<UploadResult> uploadFitOAuth(
      Session session, byte[] fitBytes, String fileName) {
    ConnectorResult<Void> valid = validateUpload(fitBytes);
    if (valid.status() != SUCCESS) return valid.castFailure();
    ConnectorResult<String> tokenRes = ensureOAuthToken(session, null);
    if (tokenRes.status() != SUCCESS) return tokenRes.castFailure();
    String token = tokenRes.value();
    String sha = hash("SHA-256", fitBytes);
    String boundary = "----FitSyncStrava" + sha.substring(0, 24);
    byte[] body;
    try {
      body = multipart(boundary,
          List.of(
              Part.file("file", safeFileName(fileName), fitBytes),
              Part.field("data_type", "fit"),
              Part.field("external_id", "fitsync-" + sha + ".fit")));
    } catch (Exception e) {
      return fail(PERMANENT_FAILURE, "UPLOAD_PREPARE_FAILED", "Strava 上传内容准备失败");
    }
    URI uploadUri = apiBase.resolve("/api/v3/uploads");
    ConnectorResult<WireResponse> response = postOAuthUpload(uploadUri, token, boundary, body);
    if (response.status() != SUCCESS) {
      return fail(
          REQUIRES_USER_ACTION,
          "UPLOAD_UNCERTAIN",
          "Strava 上传结果未知，请前往 Strava 核对活动，系统不会自动重传");
    }
    if (response.value().status() == 401) {
      ConnectorResult<String> refreshed = ensureOAuthToken(session, token);
      if (refreshed.status() != SUCCESS) return refreshed.castFailure();
      response = postOAuthUpload(uploadUri, refreshed.value(), boundary, body);
      if (response.status() != SUCCESS) {
        return fail(
            REQUIRES_USER_ACTION,
            "UPLOAD_UNCERTAIN",
            "Strava 上传结果未知，请前往 Strava 核对活动，系统不会自动重传");
      }
    }
    ConnectorResult<UploadResult> parsed = parseOAuthUploadResponse(response.value(), null, false);
    if (parsed.status() == RETRYABLE_FAILURE && !"RATE_LIMITED".equals(parsed.code())) {
      return fail(
          REQUIRES_USER_ACTION,
          "UPLOAD_UNCERTAIN",
          "Strava 上传结果未知，请前往 Strava 核对活动，系统不会自动重传");
    }
    return parsed;
  }

  private ConnectorResult<WireResponse> postOAuthUpload(
      URI uploadUri, String token, String boundary, byte[] body) {
    HttpRequest request = HttpRequest.newBuilder(uploadUri)
        .timeout(Duration.ofSeconds(60))
        .header("Authorization", "Bearer " + token)
        .header("Accept", "application/json")
        .header("Accept-Encoding", "gzip")
        .header("User-Agent", USER_AGENT)
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
    return sendWeb(request, "Strava 上传");
  }

  @Override
  public ConnectorResult<UploadResult> checkUpload(Session session, String confirmationToken) {
    if (confirmationToken == null || !UPLOAD_ID.matcher(confirmationToken).matches())
      return fail(PERMANENT_FAILURE, "INVALID_UPLOAD_ID", "Strava 上传任务 ID 无效");
    if (isOAuth(session)) {
      return checkUploadOAuth(session, confirmationToken);
    }
    // Strava upload ID 与网页会话文件哈希无关；验证仅限正整数，不能放宽为任意字符串。
    String cookie = cookie(session);
    if (cookie == null) return needsLogin();
    HttpRequest request = webRequest(
        apiBase.resolve("/upload/progress.json?ids[]=" + confirmationToken), session, cookie)
        .header("Accept", "application/json, text/javascript, */*; q=0.01")
        .header("X-Requested-With", "XMLHttpRequest")
        .GET().build();
    ConnectorResult<WireResponse> response = sendWeb(request, "Strava 上传状态");
    if (response.status() != SUCCESS) return response.castFailure();
    captureCookies(session, response.value());
    return parseUploadResponse(response.value(), confirmationToken, null);
  }

  private ConnectorResult<UploadResult> checkUploadOAuth(
      Session session, String confirmationToken) {
    ConnectorResult<WireResponse> response =
        sendOAuthGet(session, apiBase.resolve("/api/v3/uploads/" + confirmationToken), "Strava 上传状态");
    if (response.status() != SUCCESS) return response.castFailure();
    return parseOAuthUploadResponse(response.value(), confirmationToken, true);
  }

  private ConnectorResult<UploadResult> parseOAuthUploadResponse(
      WireResponse wire, String fallbackUploadId, boolean checking) {
    if (wire.status() == 401 || wire.status() == 403)
      return oauthDenied(wire, "上传请求").castFailure();
    if (wire.status() == 408 || wire.status() == 429 || wire.status() >= 500)
      return rateLimitOrServerFailure(wire, "Strava 上传");
    try {
      String raw = new String(wire.bytes(), StandardCharsets.UTF_8);
      JsonNode root = JSON.readTree(raw);
      if (root == null || root.isMissingNode())
        return fail(PERMANENT_FAILURE, "INVALID_RESPONSE", "Strava 上传响应无法解析");
      String error = root.path("error").asText("").trim();
      String status = root.path("status").asText("").trim();
      if (error.toLowerCase(Locale.ROOT).contains("duplicate")
          || status.toLowerCase(Locale.ROOT).contains("duplicate")) {
        String duplicateId = positive(root, "activity_id");
        if (duplicateId == null) duplicateId = regex(error, "(?:/activities/|activity\\s+)([1-9]\\d*)");
        if (duplicateId == null) duplicateId = regex(status, "(?:/activities/|activity\\s+)([1-9]\\d*)");
        return ConnectorResult.duplicate(duplicateId == null ? null : new UploadResult(duplicateId));
      }
      if (!error.isBlank()) {
        return fail(PERMANENT_FAILURE, "STRAVA_UPLOAD_FAILED", error);
      }
      if (wire.status() < 200 || wire.status() >= 300) {
        return fail(PERMANENT_FAILURE, "HTTP_" + wire.status(), status.isBlank() ? "Strava 拒绝了该 FIT 文件" : status);
      }
      String activityId = positive(root, "activity_id");
      if (activityId != null) return ConnectorResult.success(new UploadResult(activityId));
      String uploadId = positive(root, "id_str");
      if (uploadId == null) uploadId = positive(root, "id");
      if (uploadId == null) uploadId = fallbackUploadId;
      if (uploadId != null) {
        // 首次上传后 15s 进行初次核对（大概率一次确认）；仍在队列则退避至 60s，减少读配额消耗。
        return ConnectorResult.processing(uploadId, checking ? 60_000L : 15_000L);
      }
      return fail(PERMANENT_FAILURE, "INVALID_RESPONSE", "Strava 未返回上传任务 ID 或活动 ID");
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "INVALID_RESPONSE", "Strava 上传响应无法解析");
    }
  }

  private ConnectorResult<UploadResult> parseUploadResponse(
      WireResponse wire, String fallbackUploadId, byte[] submittedFit) {
    if (wire.status() == 401 || wire.status() == 403) return needsLogin();
    if (wire.status() == 408 || wire.status() == 429 || wire.status() >= 500)
      return statusFailure(wire.status(), "Strava 上传");
    try {
      String raw = new String(wire.bytes(), java.nio.charset.StandardCharsets.UTF_8);
      JsonNode item = raw.trim().startsWith("[") ? JSON.readTree(raw).path(0) : JSON.readTree(raw);
      if (item == null || item.isMissingNode())
        return fail(PERMANENT_FAILURE, "INVALID_RESPONSE", "Strava 上传响应无法解析");
      String workflow = item.path("workflow").asText("").toLowerCase();
      String error = item.path("error").asText("").trim();
      String duplicateUrl = item.path("duplicate_url").asText("");
      String duplicateId = regex(duplicateUrl, "/activities/([1-9]\\d*)");
      if (duplicateId != null || error.toLowerCase().contains("duplicate"))
        return ConnectorResult.duplicate(duplicateId == null ? null : new UploadResult(duplicateId));
      if (Set.of("error", "empty", "not_available", "deleted_activity").contains(workflow)
          || !error.isBlank())
        return fail(PERMANENT_FAILURE, "STRAVA_UPLOAD_FAILED",
            error.isBlank() ? "Strava 无法处理该 FIT 文件（" + workflow + "）" : error);
      String activityId = positive(item.path("activity"), "id");
      if (activityId == null) activityId = positive(item, "activity_id");
      if (activityId != null) return ConnectorResult.success(new UploadResult(activityId));
      if ("success".equals(workflow))
        return fail(PERMANENT_FAILURE, "INVALID_RESPONSE", "Strava 已处理完成，但未返回活动 ID");
      String uploadId = positive(item, "id");
      if (uploadId == null) uploadId = fallbackUploadId;
      if (uploadId != null) return ConnectorResult.processing(uploadId);
      return fail(PERMANENT_FAILURE, "INVALID_RESPONSE", "Strava 未返回上传任务 ID 或活动 ID");
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "INVALID_RESPONSE", "Strava 上传响应无法解析");
    }
  }

  private static String positive(JsonNode root, String key) {
    if (root == null) return null;
    JsonNode node = root.path(key);
    String value = node.isNumber() ? node.asText() : node.asText("").trim();
    return UPLOAD_ID.matcher(value).matches() ? value : null;
  }

  private static String regex(String text, String expression) {
    var matcher = Pattern.compile(expression, Pattern.CASE_INSENSITIVE).matcher(text);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static String extractCsrf(String html) {
    var input = Pattern.compile("(?is)<input\\b[^>]*>").matcher(html);
    while (input.find()) {
      String tag = input.group();
      if (attribute(tag, "name").equalsIgnoreCase("authenticity_token")) {
        String token = attribute(tag, "value");
        if (!token.isBlank()) return token;
      }
    }
    var meta = Pattern.compile("(?is)<meta\\b[^>]*>").matcher(html);
    while (meta.find()) {
      String tag = meta.group();
      if (attribute(tag, "name").equalsIgnoreCase("csrf-token")
          || attribute(tag, "name").equalsIgnoreCase("csrf")) {
        String token = attribute(tag, "content");
        if (!token.isBlank()) return token;
      }
    }
    return null;
  }

  private static String attribute(String tag, String name) {
    var matcher = Pattern.compile("(?is)\\b" + Pattern.quote(name) + "\\s*=\\s*([\"'])(.*?)\\1").matcher(tag);
    return matcher.find() ? matcher.group(2) : "";
  }
}
