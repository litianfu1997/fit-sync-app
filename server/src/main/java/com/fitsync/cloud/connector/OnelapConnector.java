package com.fitsync.cloud.connector;

import static com.fitsync.cloud.connector.ConnectorResult.Status.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 迈金 ONELAP 账号密码直接登录、网页会话、活动读取、原文件下载和 FIT 上传。 */
public final class OnelapConnector extends WebSessionConnector {
  private static final int PAGE_SIZE = 10;
  private static final String BASE = "https://otm.onelap.cn";
  private static final String SIGN_KEY = "fe9f8382418fcdeb136461cac6acae7b";

  public OnelapConnector() {
    this(defaultClient(), URI.create(BASE), Clock.systemUTC());
  }

  public OnelapConnector(HttpClient client, URI apiBase, Clock clock) {
    super(client, apiBase, BASE, Set.of(), clock);
  }

  @Override
  public String platform() {
    return "ONELAP";
  }

  @Override
  void authenticate(HttpRequest.Builder request, Session session) {
    if (session != null && !blank(session.accessToken())) {
      request.header("Authorization", session.accessToken());
      request.header("token", session.accessToken());
    }
  }

  @Override
  boolean businessSuccess(JsonNode root) {
    String code = text(root, "code");
    return code == null || Set.of("0", "200").contains(code);
  }

  @Override
  String businessCode(JsonNode root) {
    return text(root, "code");
  }

  @Override
  public ConnectorResult<Session> login(
      String username, String password, boolean storeCredentials) {
    if (blank(username) || blank(password))
      return fail(PERMANENT_FAILURE, "CREDENTIALS_REQUIRED", "请输入迈金账号和密码");
    String account = username.trim();
    String md5Password = hash("MD5", password.getBytes(StandardCharsets.UTF_8));
    String nonce =
        hash("MD5", (account + ":" + clock.millis()).getBytes(StandardCharsets.UTF_8))
            .substring(0, 16);
    String timestamp = Long.toString(clock.instant().getEpochSecond());
    String sign =
        hash(
            "MD5",
            ("account="
                    + account
                    + "&nonce="
                    + nonce
                    + "&password="
                    + md5Password
                    + "&timestamp="
                    + timestamp
                    + "&key="
                    + SIGN_KEY)
                .getBytes(StandardCharsets.UTF_8));
    byte[] payload;
    try {
      payload = JSON.writeValueAsBytes(Map.of("account", account, "password", md5Password));
    } catch (Exception e) {
      return fail(PERMANENT_FAILURE, "LOGIN_PREPARE_FAILED", "迈金登录请求构建失败");
    }
    HttpRequest request =
        HttpRequest.newBuilder(api("/api/login"))
            .timeout(java.time.Duration.ofSeconds(60))
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", BASE)
            .header("Referer", BASE + "/login")
            .header("User-Agent", browserAgent(null))
            .header("nonce", nonce)
            .header("timestamp", timestamp)
            .header("sign", sign)
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();
    ConnectorResult<WireResponse> response = sendWeb(request, "迈金登录");
    if (response.status() != SUCCESS) return response.castFailure();
    WireResponse wire = response.value();
    if (wire.status() == 401 || wire.status() == 403)
      return fail(PERMANENT_FAILURE, "AUTH_FAILED", "迈金账号或密码错误");
    if (wire.status() == 408 || wire.status() == 429 || wire.status() >= 500)
      return statusFailure(wire.status(), "迈金登录");
    if (wire.status() < 200 || wire.status() >= 300)
      return fail(PERMANENT_FAILURE, "HTTP_" + wire.status(), "迈金登录失败");
    JsonNode root;
    try {
      root = JSON.readTree(wire.bytes());
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "INVALID_RESPONSE", "迈金登录响应格式异常");
    }
    if (root == null || !root.isObject())
      return fail(RETRYABLE_FAILURE, "INVALID_RESPONSE", "迈金登录响应格式异常");
    if (!businessSuccess(root)) {
      String message = text(root, "error", "message", "msg");
      return fail(
          PERMANENT_FAILURE,
          "ONELAP_AUTH_FAILED",
          blank(message) ? "迈金账号或密码错误" : "迈金登录失败：" + message);
    }
    JsonNode data = root.path("data");
    JsonNode node =
        data.isArray() && !data.isEmpty() ? data.get(0) : (data.isObject() ? data : root);
    String token = text(node, "token", "access_token");
    if (blank(token))
      return fail(REQUIRES_USER_ACTION, "LOGIN_PROTOCOL_CHANGED", "迈金未返回有效登录令牌，请检查账号状态");
    String refreshToken = text(node, "refresh_token", "refreshToken");
    String uid = text(node, "uid", "user_id", "id");
    if (blank(uid)) uid = extractUidFromJwt(token);
    String cookie = mergeCookies(null, wire.cookies());
    Map<String, String> values = new LinkedHashMap<>();
    if (!blank(refreshToken)) values.put("refreshToken", refreshToken);
    if (!blank(cookie)) values.put("cookie", cookie);
    return ConnectorResult.success(
        new Session(token, uid, account, password, storeCredentials, values));
  }

  private static String extractUidFromJwt(String token) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length < 2 || parts[1].length() > 32768) return null;
      JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
      return text(payload, "uid", "user_id", "id");
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  public ConnectorResult<Session> connectWeb(Map<String, String> input) {
    if (!validFields(input)) return invalidSession("网页会话字段无效或过长");
    String token = required(input, "token");
    if (token == null) return invalidSession("请填写迈金网页 localStorage 中的 token");
    Map<String, String> values = attributes(input);
    Session session = new Session(token, optional(input, "externalUserId"), null, null, false, values);
    ConnectorResult<ActivityPage> probe = listActivities(session, 0, null);
    if (probe.status() != SUCCESS) return probe.castFailure();
    return ConnectorResult.success(session);
  }

  private static Map<String, String> attributes(Map<String, String> input) {
    Map<String, String> values = new LinkedHashMap<>();
    for (String key : List.of("cookie", "refreshToken", "userAgent")) {
      String value = optional(input, key);
      if (value != null) values.put(key, value);
    }
    return values;
  }

  @Override
  public ConnectorResult<ActivityPage> listActivities(
      Session session, long sinceEpochMs, String cursor) {
    if (session == null || blank(session.accessToken())) return needsLogin();
    int page = pageNumber(cursor);
    try {
      String today = LocalDate.now(clock.withZone(CHINA)).format(DateTimeFormatter.ISO_LOCAL_DATE);
      String since = java.time.Instant.ofEpochMilli(Math.max(0, sinceEpochMs))
          .atZone(CHINA).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
      byte[] body = JSON.writeValueAsBytes(Map.of(
          "page", page, "limit", PAGE_SIZE, "start_date", since, "end_date", today));
      ConnectorResult<JsonNode> result = callOnelap(session, "POST", api("/api/otm/ride_record/list"), body,
          "application/json;charset=UTF-8");
      if (result.status() != SUCCESS) return result.castFailure();
      JsonNode rows = array(result.value(), "list", "rows", "records", "data");
      if (rows == null) return fail(RETRYABLE_FAILURE, "INVALID_ACTIVITY_LIST", "迈金活动列表格式已变化");
      List<RemoteActivity> items = new ArrayList<>();
      for (JsonNode row : rows) {
        String id = nestedText(row, "workout_id", "ride_record_id", "record_id", "jilu_id", "id");
        if (blank(id)) continue;
        Long start = nestedStartTime(row, "start_riding_time", "start_time", "startTime", "start_date", "timestamp", "create_time");
        if (start != null && start < sinceEpochMs) continue;
        String fit = nestedText(row, "fit_url", "fitUrl", "file_url", "download_url");
        Map<String, String> metadata = fit == null ? Map.of() : Map.of("fit_url", fit);
        Double dist = nestedDistance(row, "distance", "distance_meter", "total_distance");
        if (dist == null) {
          Double km = nestedDistance(row, "distance_km");
          if (km != null) dist = km * 1000.0;
        }
        items.add(new RemoteActivity(id, start, nestedText(row, "name", "title", "record_name"),
            dist, fit, metadata));
      }
      boolean more = rows.size() >= PAGE_SIZE;
      return ConnectorResult.success(new ActivityPage(items, Integer.toString(page + 1), more));
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "INVALID_ACTIVITY_LIST", "迈金活动列表读取失败");
    }
  }

  @Override
  public ConnectorResult<FitDownload> downloadFit(Session session, RemoteActivity activity) {
    String fit = activity.downloadUrl();
    if (blank(fit)) fit = activity.metadata().get("fit_url");
    if (blank(fit)) {
      ConnectorResult<JsonNode> detail = callOnelap(session, "GET",
          api("/api/otm/ride_record/analysis/" + q(activity.remoteActivityId())), null, null);
      if (detail.status() != SUCCESS) return detail.castFailure();
      fit = nestedText(detail.value(), "fitUrl", "fit_url", "file_url", "download_url");
      if (blank(fit)) return fail(PERMANENT_FAILURE, "NO_ORIGINAL_FILE", "迈金未返回 FIT 下载地址");
    }
    String encoded = Base64.getEncoder().encodeToString(fit.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    ConnectorResult<WireResponse> response = sendWeb(webRequest(
        api("/api/otm/ride_record/analysis/fit_content/" + encoded), session, cookie(session)).GET().build(), "迈金 FIT 下载");
    if (response.status() != SUCCESS) return response.castFailure();
    WireResponse value = response.value();
    captureCookies(session, value);
    if (value.status() == 404 || value.status() == 410)
      return fail(PERMANENT_FAILURE, "NO_ORIGINAL_FILE", "迈金未保留该活动的原始 FIT");
    if (value.status() == 401 || value.status() == 403) return needsLogin();
    if (value.status() < 200 || value.status() >= 300) return statusFailure(value.status(), "迈金 FIT 下载");
    String type = value.header("Content-Type");
    if (type != null && type.toLowerCase().contains("json")) {
      try {
        JsonNode root = JSON.readTree(value.bytes());
        String url = nestedText(root, "url", "fit_url", "download_url", "data");
        if (blank(url)) return fail(PERMANENT_FAILURE, "NO_ORIGINAL_FILE", "迈金未返回 FIT 文件");
        return downloadObject(url, "onelap_" + activity.remoteActivityId() + ".fit");
      } catch (Exception e) {
        return fail(RETRYABLE_FAILURE, "INVALID_DOWNLOAD_RESPONSE", "迈金下载地址响应异常");
      }
    }
    byte[] fitBytes;
    try {
      fitBytes = extractFit(value.bytes());
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "FIT_DOWNLOAD_FAILED", "迈金 FIT 读取失败");
    }
    if (fitBytes == null) return fail(PERMANENT_FAILURE, "INVALID_FIT", "迈金返回的内容不是有效 FIT");
    return ConnectorResult.success(new FitDownload(fitBytes, "onelap_" + activity.remoteActivityId() + ".fit"));
  }

  @Override
  protected boolean trustedObjectUrl(String url) {
    try {
      String host = URI.create(url).getHost();
      return super.trustedObjectUrl(url) || host != null
          && (host.equals("onelap.cn") || host.endsWith(".onelap.cn")
              || host.equals("aliyuncs.com") || host.endsWith(".aliyuncs.com")
              || host.equals("myqcloud.com") || host.endsWith(".myqcloud.com"))
          && URI.create(url).getScheme().equals("https")
          && URI.create(url).getUserInfo() == null
          && (URI.create(url).getPort() == -1 || URI.create(url).getPort() == 443);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private ConnectorResult<UploadResult> pendingUpload(byte[] fitBytes) {
    return ConnectorResult.processing(pendingToken(fitBytes, "1"));
  }

  @Override
  public ConnectorResult<UploadResult> uploadFit(Session session, byte[] fitBytes, String fileName) {
    ConnectorResult<Void> valid = validateUpload(fitBytes);
    if (valid.status() != SUCCESS) return valid.castFailure();
    String sha = hash("SHA-256", fitBytes);
    String boundary = "----WebKitFormBoundary" + sha.substring(0, 24);
    ConnectorResult<UploadResult> last = null;
    for (String field : List.of("jilu0", "jilu", "file", "fit_file")) {
      try {
        byte[] body = multipart(boundary, List.of(Part.file(field, "fitsync_" + sha.substring(0, 16) + ".fit", fitBytes)));
        ConnectorResult<JsonNode> result = callOnelap(session, "POST", api("/api/otm/ride_record/upload/fit"), body,
            "multipart/form-data; boundary=" + boundary);
        if (result.status() == RETRYABLE_FAILURE || result.status() == REQUIRES_USER_ACTION)
          return pendingUpload(fitBytes);
        if (result.status() != SUCCESS) return result.castFailure();
        JsonNode root = result.value();
        String id = nestedText(root, "id", "record_id", "ride_record_id", "jilu_id");
        if (!blank(id)) return ConnectorResult.success(new UploadResult(id));
        String message = text(root, "message", "msg", "detail", "error");
        if (message != null && (message.contains("没有上传文件") || message.contains("没有文件上传"))) {
          last = fail(PERMANENT_FAILURE, "ONELAP_FILE_FIELD_REJECTED", "迈金未识别上传文件字段");
          continue;
        }
        return pendingUpload(fitBytes);
      } catch (Exception e) {
        return pendingUpload(fitBytes);
      }
    }
    return last == null ? fail(PERMANENT_FAILURE, "ONELAP_UPLOAD_REJECTED", "迈金拒绝 FIT 上传") : last;
  }

  private ConnectorResult<JsonNode> callOnelap(
      Session session, String method, URI uri, byte[] body, String contentType) {
    ConnectorResult<JsonNode> first = callOnce(session, method, uri, body, contentType);
    if (first.status() != REQUIRES_LOGIN || session == null) return first;
    String refresh = session.attribute("refreshToken");
    if (!blank(refresh)) {
      try {
        byte[] payload = JSON.writeValueAsBytes(
            Map.of("token", refresh, "from", "web", "to", "web"));
        ConnectorResult<JsonNode> renewed = callOnce(session, "POST", api("/api/token"), payload,
            "application/json;charset=UTF-8");
        if (renewed.status() == SUCCESS) {
          JsonNode data = renewed.value().path("data").isObject()
              ? renewed.value().path("data") : renewed.value();
          String token = text(data, "token", "access_token");
          if (!blank(token)) {
            session.setAccessToken(token);
            String rotated = text(data, "refresh_token", "refreshToken");
            if (!blank(rotated)) session.setAttribute("refreshToken", rotated);
            return callOnce(session, method, uri, body, contentType);
          }
        }
      } catch (Exception ignored) {
      }
    }
    if (reloginIfPossible(session)) {
      return callOnce(session, method, uri, body, contentType);
    }
    return first;
  }

  private static final List<String> NESTED_KEYS =
      List.of("data", "ridingRecord", "riding_record", "rideRecord", "record", "activity", "detail");

  private static String nestedText(JsonNode root, String... keys) {
    return nestedText(root, 0, keys);
  }

  private static String nestedText(JsonNode root, int depth, String... keys) {
    if (root == null || !root.isObject() || depth > 3) return null;
    String direct = text(root, keys);
    if (direct != null) return direct;
    for (String container : NESTED_KEYS) {
      JsonNode child = root.path(container);
      if (child.isObject()) {
        String nested = nestedText(child, depth + 1, keys);
        if (nested != null) return nested;
      }
    }
    return null;
  }

  private static Long nestedStartTime(JsonNode root, String... keys) {
    return nestedStartTime(root, 0, keys);
  }

  private static Long nestedStartTime(JsonNode root, int depth, String... keys) {
    if (root == null || !root.isObject() || depth > 3) return null;
    Long direct = startTime(root, keys);
    if (direct != null) return direct;
    for (String container : NESTED_KEYS) {
      JsonNode child = root.path(container);
      if (child.isObject()) {
        Long nested = nestedStartTime(child, depth + 1, keys);
        if (nested != null) return nested;
      }
    }
    return null;
  }

  private static Double nestedDistance(JsonNode root, String... keys) {
    return nestedDistance(root, 0, keys);
  }

  private static Double nestedDistance(JsonNode root, int depth, String... keys) {
    if (root == null || !root.isObject() || depth > 3) return null;
    Double direct = distance(root, keys);
    if (direct != null) return direct;
    for (String container : NESTED_KEYS) {
      JsonNode child = root.path(container);
      if (child.isObject()) {
        Double nested = nestedDistance(child, depth + 1, keys);
        if (nested != null) return nested;
      }
    }
    return null;
  }

  private static JsonNode array(JsonNode root, String... keys) {
    for (String key : keys) {
      JsonNode value = root.path(key);
      if (value.isArray()) return value;
      if (value.isObject()) {
        JsonNode nested = array(value, keys);
        if (nested != null) return nested;
      }
    }
    return null;
  }
}
