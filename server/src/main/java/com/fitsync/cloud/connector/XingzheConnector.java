package com.fitsync.cloud.connector;

import static com.fitsync.cloud.connector.ConnectorResult.Status.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;

/** 行者账号密码直接登录、网页会话、活动分页、FIT 下载与上传。 */
public final class XingzheConnector extends WebSessionConnector {
  private static final int PAGE_SIZE = 50;
  private static final String BASE = "https://www.imxingzhe.com";
  private static final String PUBLIC_KEY_BASE64 =
      "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDmuQkBbijudDAJgfffDeeIButqWHZvUwcRuvWdg89393FSdz3IJUHc0rgI/S3WuU8N0VePJLmVAZtCOK4qe4FY/eKmWpJmn7JfXB4HTMWjPVoyRZmSYjW4L8GrWmh51Qj7DwpTADadF3aq04o+s1b8LXJa8r6+TIqqL5WUHtRqmQIDAQAB";

  public XingzheConnector() {
    this(defaultClient(), URI.create(BASE), Clock.systemUTC());
  }

  public XingzheConnector(HttpClient client, URI apiBase, Clock clock) {
    super(client, apiBase, BASE, Set.of(), clock);
  }

  @Override
  public String platform() {
    return "XINGZHE";
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

  private static HttpRequest.Builder cookieRequest(
      HttpRequest.Builder builder, Map<String, String> values) {
    String cookie = values.get("cookie");
    if (cookie != null && !cookie.isBlank()) builder.header("Cookie", cookie);
    return builder;
  }

  @Override
  public ConnectorResult<Session> login(
      String username, String password, boolean storeCredentials) {
    if (blank(username) || blank(password))
      return fail(PERMANENT_FAILURE, "CREDENTIALS_REQUIRED", "请输入行者账号和密码");
    String account = username.trim();
    String encryptedPassword;
    try {
      byte[] keyBytes = Base64.getDecoder().decode(PUBLIC_KEY_BASE64);
      PublicKey publicKey =
          KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
      Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
      cipher.init(Cipher.ENCRYPT_MODE, publicKey);
      encryptedPassword =
          Base64.getEncoder().encodeToString(cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      return fail(PERMANENT_FAILURE, "LOGIN_PREPARE_FAILED", "行者登录凭据加密失败");
    }
    byte[] payload;
    try {
      payload = JSON.writeValueAsBytes(Map.of("account", account, "password", encryptedPassword));
    } catch (Exception e) {
      return fail(PERMANENT_FAILURE, "LOGIN_PREPARE_FAILED", "行者登录请求构建失败");
    }
    HttpRequest request =
        HttpRequest.newBuilder(api("/api/v1/user/login/"))
            .timeout(java.time.Duration.ofSeconds(60))
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", BASE)
            .header("Referer", BASE + "/login/")
            .header("User-Agent", browserAgent(null))
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();
    ConnectorResult<WireResponse> response = sendWeb(request, "行者登录");
    if (response.status() != SUCCESS) return response.castFailure();
    WireResponse wire = response.value();
    if (wire.status() == 401 || wire.status() == 403)
      return fail(PERMANENT_FAILURE, "XINGZHE_LOGIN_FAILED", "行者账号或密码错误");
    if (wire.status() == 408 || wire.status() == 429 || wire.status() >= 500)
      return statusFailure(wire.status(), "行者登录");
    if (wire.status() < 200 || wire.status() >= 300)
      return fail(PERMANENT_FAILURE, "HTTP_" + wire.status(), "行者登录失败");
    String cookie = mergeCookies(null, wire.cookies());
    JsonNode root = null;
    if (wire.bytes() != null && wire.bytes().length > 0) {
      try {
        root = JSON.readTree(wire.bytes());
      } catch (Exception ignored) {
      }
    }
    if (root != null && root.isObject() && root.has("code") && root.path("code").asInt(0) != 0) {
      String message = text(root, "msg", "message", "detail");
      return fail(
          PERMANENT_FAILURE,
          "XINGZHE_LOGIN_FAILED",
          blank(message) ? "行者账号或密码错误" : "行者登录失败：" + message);
    }
    JsonNode data = root != null ? root.path("data") : null;
    String bodySession =
        text(data, "sessionid", "session_id");
    if (blank(bodySession) && root != null) {
      bodySession = text(root, "sessionid", "session_id");
    }
    if (!blank(bodySession) && cookie(cookie, "sessionid") == null) {
      cookie = mergeCookies(cookie, List.of("sessionid=" + bodySession));
    }
    if (blank(cookie)) {
      return fail(REQUIRES_USER_ACTION, "LOGIN_PROTOCOL_CHANGED", "行者登录未返回有效会话 Cookie");
    }
    String uid = data != null ? text(data, "userid", "user_id", "id", "uid") : null;
    if (blank(uid) && root != null) uid = text(root, "userid", "user_id", "id", "uid");
    return ConnectorResult.success(
        new Session(null, uid, account, password, storeCredentials, Map.of("cookie", cookie)));
  }

  @Override
  public ConnectorResult<Session> connectWeb(Map<String, String> input) {
    if (!validFields(input)) return invalidSession("网页会话字段无效或过长");
    String cookie = required(input, "cookie");
    if (cookie == null) return invalidSession("请填写行者网页登录后的 Cookie");
    Map<String, String> values = new LinkedHashMap<>();
    values.put("cookie", cookie);
    String agent = optional(input, "userAgent");
    if (agent != null) values.put("userAgent", agent);
    Session session = new Session(null, optional(input, "externalUserId"), null, null, false, values);
    ConnectorResult<ActivityPage> probe = listActivities(session, 0, null);
    if (probe.status() != SUCCESS) return probe.castFailure();
    return ConnectorResult.success(session);
  }

  @Override
  public ConnectorResult<ActivityPage> listActivities(
      Session session, long sinceEpochMs, String cursor) {
    ConnectorResult<ActivityPage> first = listActivitiesOnce(session, sinceEpochMs, cursor);
    if (first.status() == REQUIRES_LOGIN && reloginIfPossible(session)) {
      return listActivitiesOnce(session, sinceEpochMs, cursor);
    }
    return first;
  }

  private ConnectorResult<ActivityPage> listActivitiesOnce(
      Session session, long sinceEpochMs, String cursor) {
    String cookie = cookie(session);
    if (cookie == null) return needsLogin();
    int offset = Math.max(0, pageNumber(cursor) - 1) * PAGE_SIZE;
    String url = apiBase + "/api/v1/pgworkout/?limit=" + PAGE_SIZE + "&offset=" + offset;
    HttpRequest request =
        cookieRequest(
                webRequest(URI.create(url), session, cookie).header("Accept", "application/json,*/*"),
                Map.of("cookie", cookie))
            .GET()
            .build();
    ConnectorResult<WireResponse> response = sendWeb(request, "行者活动列表");
    if (response.status() != SUCCESS) return response.castFailure();
    captureCookies(session, response.value());
    ConnectorResult<JsonNode> decoded = json(response.value(), "行者活动列表");
    if (decoded.status() != SUCCESS) return decoded.castFailure();
    JsonNode root = decoded.value();
    JsonNode rows = root.isArray() ? root : firstArray(root, "list", "rows", "results", "items", "records", "data");
    if (rows == null) return fail(RETRYABLE_FAILURE, "INVALID_ACTIVITY_LIST", "行者活动列表格式已变化");
    List<RemoteActivity> items = new ArrayList<>();
    for (JsonNode row : rows) {
      String id = text(row, "workout_id", "id", "record_id");
      if (blank(id)) continue;
      Long start = startTime(row, "start_riding_time", "start_time", "start_date", "timestamp", "create_time");
      if (start != null && start < sinceEpochMs) continue;
      String fit = text(row, "fit_url", "fitUrl", "file_url", "download_url");
      Map<String, String> metadata = fit == null ? Map.of() : Map.of("fit_url", fit);
      items.add(new RemoteActivity(id, start, text(row, "name", "title"),
          distance(row, "distance", "distance_meter", "total_distance"), fit, metadata));
    }
    boolean more = shouldContinue(rows.size(), items.size());
    return ConnectorResult.success(
        new ActivityPage(items, Integer.toString(pageNumber(cursor) + 1), more));
  }

  private static boolean shouldContinue(int raw, int eligible) {
    return raw >= PAGE_SIZE && eligible > 0;
  }

  @Override
  public ConnectorResult<FitDownload> downloadFit(Session session, RemoteActivity activity) {
    ConnectorResult<FitDownload> first = downloadFitOnce(session, activity);
    if (first.status() == REQUIRES_LOGIN && reloginIfPossible(session)) {
      return downloadFitOnce(session, activity);
    }
    return first;
  }

  private ConnectorResult<FitDownload> downloadFitOnce(Session session, RemoteActivity activity) {
    String cookie = cookie(session);
    if (cookie == null) return needsLogin();
    URI uri = apiBase.resolve("/api/v1/workout/" + q(activity.remoteActivityId()) + "/fit/");
    HttpRequest request =
        cookieRequest(webRequest(uri, session, cookie), Map.of("cookie", cookie)).GET().build();
    return downloadBytes(request, "xingzhe_" + activity.remoteActivityId() + ".fit");
  }

  @Override
  public ConnectorResult<UploadResult> uploadFit(Session session, byte[] fitBytes, String fileName) {
    ConnectorResult<Void> valid = validateUpload(fitBytes);
    if (valid.status() != SUCCESS) return valid.castFailure();
    String cookie = cookie(session);
    if (cookie == null) return needsLogin();
    String sha = hash("SHA-256", fitBytes);
    String boundary = "FitSync" + sha.substring(0, 20);
    byte[] body;
    try {
      body = multipart(boundary,
          List.of(
              Part.field("file_source", "FitSync"),
              Part.field("fit_filename", safeFileName(fileName)),
              Part.field("md5", hash("MD5", fitBytes)),
              Part.field("name", fileName.replaceFirst("(?i)\\.fit$", "")),
              Part.field("sport", "3"),
              Part.file("fit_file", safeFileName(fileName), fitBytes)));
    } catch (Exception e) {
      return fail(PERMANENT_FAILURE, "UPLOAD_PREPARE_FAILED", "行者上传内容准备失败");
    }
    HttpRequest request = cookieRequest(webRequest(api("/api/v1/fit/upload/"), session, cookie)
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body)), Map.of("cookie", cookie)).build();
    ConnectorResult<WireResponse> response = sendWeb(request, "行者上传");
    if (response.status() != SUCCESS) return uncertainUpload(fitBytes, "1");
    captureCookies(session, response.value());
    WireResponse wire = response.value();
    if (wire.status() == 401 || wire.status() == 403 || looksLikeLoginPage(wire.bytes()))
      return needsLogin();
    if (wire.status() == 408 || wire.status() == 429 || wire.status() >= 500)
      return uncertainUpload(fitBytes, "1");
    try {
      JsonNode root = JSON.readTree(wire.bytes());
      int code = root.path("code").asInt(wire.status());
      if (code == 9006) {
        String message = text(root, "msg", "message");
        String id = message == null ? null : message.replaceAll("[^0-9]", "");
        if (blank(id)) return uncertainUpload(fitBytes, "1");
        return ConnectorResult.duplicate(new UploadResult(id));
      }
      if (wire.status() < 200 || wire.status() >= 300 || code != 0) {
        String message = text(root, "message", "msg", "detail");
        if (message != null && message.toLowerCase().contains("authentication")) return needsLogin();
        return fail(PERMANENT_FAILURE, "XINGZHE_UPLOAD_REJECTED",
            message == null ? "行者拒绝该 FIT 上传" : "行者拒绝上传：" + message);
      }
      String id = text(root, "workout_id", "id");
      if (blank(id)) id = text(root.path("data"), "workout_id", "id");
      if (blank(id)) return uncertainUpload(fitBytes, "1");
      return ConnectorResult.success(new UploadResult(id));
    } catch (Exception e) {
      return uncertainUpload(fitBytes, "1");
    }
  }

  private static boolean looksLikeLoginPage(byte[] bytes) {
    String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).toLowerCase();
    return text.contains("<!doctype html") && text.contains("login");
  }

  private static JsonNode firstArray(JsonNode root, String... keys) {
    for (String key : keys) {
      JsonNode node = root.path(key);
      if (node.isArray()) return node;
      if (node.isObject()) {
        JsonNode nested = firstArray(node, keys);
        if (nested != null) return nested;
      }
    }
    return null;
  }
}
