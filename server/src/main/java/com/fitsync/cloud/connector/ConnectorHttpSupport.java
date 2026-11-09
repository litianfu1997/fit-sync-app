package com.fitsync.cloud.connector;

import static com.fitsync.cloud.connector.ConnectorResult.Status.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** 两个平台共用的有界网络、会话续期与异步确认；不会记录敏感请求。 */
abstract class ConnectorHttpSupport implements PlatformConnector {
  static final int MAX_FIT_BYTES = 50 * 1024 * 1024;
  static final int MAX_UPLOAD_BYTES = 25 * 1024 * 1024;
  static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
  static final ObjectMapper JSON = new ObjectMapper();
  static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
  static final String USER_AGENT = "Mozilla/5.0 FitSync/1.0";
  final HttpClient client;
  final URI apiBase;
  final Clock clock;
  private final String origin;
  private final Set<String> objectHosts;

  ConnectorHttpSupport(
      HttpClient client, URI apiBase, String origin, Set<String> objectHosts, Clock clock) {
    if (client.followRedirects() != HttpClient.Redirect.NEVER) {
      throw new IllegalArgumentException("连接器必须禁用自动重定向，以保护凭据和文件白名单");
    }
    this.client = client;
    this.apiBase = apiBase;
    this.origin = origin;
    this.objectHosts = Set.copyOf(objectHosts);
    this.clock = clock;
  }

  static HttpClient defaultClient() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  URI api(String path) {
    return apiBase.resolve(path);
  }

  static String q(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8);
  }

  abstract void authenticate(HttpRequest.Builder request, Session session);

  abstract boolean businessSuccess(JsonNode root);

  abstract String businessCode(JsonNode root);

  ConnectorResult<JsonNode> loginRequest(String path, Object payload) {
    try {
      return decodeApi(
          send(
              request(api(path), null)
                  .header("Content-Type", "application/json;charset=UTF-8")
                  .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(payload)))
                  .build(),
              MAX_JSON_BYTES));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return fail(RETRYABLE_FAILURE, "INTERRUPTED", "平台登录请求被中断，请稍后重试");
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "NETWORK", "暂时无法连接平台登录服务");
    }
  }

  /** 只重试明确返回未授权的那一个请求，防止已受理的上传被整条流水线重复执行。 */
  ConnectorResult<JsonNode> call(
      Session session, String method, URI uri, byte[] body, String contentType) {
    ConnectorResult<JsonNode> first = callOnce(session, method, uri, body, contentType);
    if (first.status() != REQUIRES_LOGIN
        || session == null
        || !session.storeCredentials()
        || blank(session.username())
        || blank(session.password())) return first;
    ConnectorResult<Session> renewed = login(session.username(), session.password(), true);
    if (renewed.status() != SUCCESS) return renewed.castFailure();
    session.updateFrom(renewed.value());
    return callOnce(session, method, uri, body, contentType);
  }

  private ConnectorResult<JsonNode> callOnce(
      Session session, String method, URI uri, byte[] body, String contentType) {
    if (session == null || blank(session.accessToken())) return needsLogin();
    try {
      HttpRequest.Builder request = request(uri, session);
      if (contentType != null) request.header("Content-Type", contentType);
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(body));
      return decodeApi(send(request.build(), MAX_JSON_BYTES));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return fail(RETRYABLE_FAILURE, "INTERRUPTED", "平台请求被中断，请稍后重试");
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "NETWORK", "平台请求暂时失败，请稍后重试");
    }
  }

  HttpRequest.Builder request(URI uri, Session session) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", origin)
            .header("Referer", origin + "/")
            .header("User-Agent", USER_AGENT);
    if (session != null) authenticate(builder, session);
    return builder;
  }

  ConnectorResult<JsonNode> decodeApi(WireResponse response) throws IOException {
    if (response.status == 401 || response.status == 403) return needsLogin();
    if (response.status < 200 || response.status >= 300)
      return statusFailure(response.status, "平台接口");
    JsonNode root = JSON.readTree(response.bytes);
    if (root == null || !root.isObject())
      return fail(RETRYABLE_FAILURE, "INVALID_RESPONSE", "平台返回了无法识别的响应");
    if (businessSuccess(root)) return ConnectorResult.success(root);
    String code = businessCode(root);
    if (code == null) return fail(RETRYABLE_FAILURE, "INVALID_RESPONSE", "平台响应缺少请求状态，请等待确认后重试");
    String message = text(root, "message", "msg");
    String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
    if (Set.of("401", "403", "0401", "0403").contains(code == null ? "" : code)
        || (lower.contains("token")
            && (lower.contains("expired")
                || lower.contains("invalid")
                || lower.contains("失效")
                || lower.contains("过期")))) return needsLogin();
    return fail(PERMANENT_FAILURE, code, "平台未接受请求，请在平台网页确认账号和活动状态");
  }

  WireResponse send(HttpRequest request, int maxBytes) throws IOException, InterruptedException {
    HttpResponse<InputStream> response =
        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream stream = response.body()) {
      long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
      if (length > maxBytes) throw new IOException("响应超过大小限制");
      return new WireResponse(response.statusCode(), readBounded(stream, maxBytes));
    }
  }

  /** 对象服务的失败不代表账号会话过期，也绝不带用户 token。 */
  ConnectorResult<FitDownload> downloadObject(String url, String fileName) {
    if (!trustedObjectUrl(url))
      return fail(PERMANENT_FAILURE, "UNTRUSTED_FIT_HOST", "平台文件地址不在 HTTPS 白名单内");
    try {
      WireResponse response =
          send(
              HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90)).GET().build(),
              MAX_FIT_BYTES);
      if (response.status == 404 || response.status == 410)
        return fail(PERMANENT_FAILURE, "NO_ORIGINAL_FILE", "平台未保留该活动的原始 FIT 文件");
      if (response.status < 200 || response.status >= 300)
        return fail(RETRYABLE_FAILURE, "OBJECT_HTTP_" + response.status, "平台文件服务暂时不可用");
      byte[] fit = extractFit(response.bytes);
      if (fit == null) return fail(PERMANENT_FAILURE, "INVALID_FIT", "平台返回的内容不是 FIT 或单文件 FIT 压缩包");
      return ConnectorResult.success(new FitDownload(fit, safeFileName(fileName)));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return fail(RETRYABLE_FAILURE, "INTERRUPTED", "FIT 下载被中断");
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "FIT_DOWNLOAD_FAILED", "FIT 下载失败或超过大小限制");
    }
  }

  boolean trustedObjectUrl(String url) {
    try {
      URI uri = URI.create(url);
      return "https".equalsIgnoreCase(uri.getScheme())
          && uri.getUserInfo() == null
          && uri.getFragment() == null
          && (uri.getPort() == -1 || uri.getPort() == 443)
          && uri.getHost() != null
          && objectHosts.contains(uri.getHost().toLowerCase(Locale.ROOT));
    } catch (RuntimeException e) {
      return false;
    }
  }

  ConnectorResult<Void> validateUpload(byte[] fit) {
    if (fit == null || fit.length == 0) return fail(PERMANENT_FAILURE, "FILE_EMPTY", "FIT 文件为空");
    if (fit.length > MAX_UPLOAD_BYTES)
      return fail(PERMANENT_FAILURE, "FILE_TOO_LARGE", "平台限制 FIT 文件不超过 25 MB");
    if (!isFit(fit)) return fail(PERMANENT_FAILURE, "INVALID_FIT", "上传内容不是有效的 FIT 文件");
    return ConnectorResult.success(null);
  }

  /** POST 已发出后遇到网络或服务端不明确状态，保留 token 等待确认，不能重新提交。 */
  ConnectorResult<UploadResult> submit(
      Session session, URI uri, byte[] body, String contentType, String token) {
    ConnectorResult<JsonNode> result = call(session, "POST", uri, body, contentType);
    if (result.status() == SUCCESS || result.status() == RETRYABLE_FAILURE)
      return ConnectorResult.processing(token);
    return result.castFailure();
  }

  String confirmationToken(Session session, byte[] bytes, String hint) throws IOException {
    return encodeConfirmation(
        new Confirmation(
            platform(),
            session.externalUserId(),
            hash("SHA-256", bytes),
            hint,
            clock.millis(),
            "1",
            0,
            0));
  }

  @Override
  public ConnectorResult<UploadResult> checkUpload(Session session, String confirmationToken) {
    try {
      if (confirmationToken == null || confirmationToken.length() > 4096)
        return invalidConfirmation();
      Confirmation token =
          JSON.readValue(Base64.getUrlDecoder().decode(confirmationToken), Confirmation.class);
      if (!platform().equals(token.platform)
          || token.sha256 == null
          || !token.sha256.matches("[a-f0-9]{64}")
          || token.submittedAt > clock.millis() + 60_000
          || token.submittedAt <= 0
          || !java.util.Objects.equals(token.externalUserId, session.externalUserId()))
        return invalidConfirmation();
      if (clock.millis() - token.submittedAt > Duration.ofHours(24).toMillis()
          || token.checks >= 30) {
        return fail(REQUIRES_USER_ACTION, "CONFIRMATION_REQUIRED", "上传尚未确认，请前往平台核对活动，避免重复投递");
      }
      ConnectorResult<ActivityPage> pageResult = listActivities(session, 0, token.cursor);
      if (pageResult.status() != SUCCESS) return pageResult.castFailure();
      ActivityPage page = pageResult.value();
      int inspected = 0;
      int offset = Math.max(0, token.offset);
      for (; offset < page.items().size() && inspected < 5; offset++) {
        RemoteActivity candidate = page.items().get(offset);
        inspected++;
        ConnectorResult<FitDownload> file = downloadFit(session, candidate);
        if (file.status() == REQUIRES_LOGIN) return file.castFailure();
        if (file.status() == SUCCESS
            && token.sha256.equals(hash("SHA-256", file.value().bytes()))) {
          return ConnectorResult.success(new UploadResult(candidate.remoteActivityId()));
        }
      }
      String cursor = token.cursor;
      if (offset >= page.items().size()) {
        cursor = page.hasMore() ? page.nextCursor() : "1";
        offset = 0;
      }
      return ConnectorResult.processing(
          encodeConfirmation(
              new Confirmation(
                  token.platform,
                  token.externalUserId,
                  token.sha256,
                  token.hint,
                  token.submittedAt,
                  cursor,
                  offset,
                  token.checks + 1)));
    } catch (Exception e) {
      return invalidConfirmation();
    }
  }

  private String encodeConfirmation(Confirmation token) throws IOException {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(token));
  }

  private ConnectorResult<UploadResult> invalidConfirmation() {
    return fail(REQUIRES_USER_ACTION, "INVALID_CONFIRMATION", "上传确认信息无效，请在平台核对活动状态");
  }

  record Confirmation(
      String platform,
      String externalUserId,
      String sha256,
      String hint,
      long submittedAt,
      String cursor,
      int offset,
      int checks) {}

  record WireResponse(int status, byte[] bytes) {}

  static byte[] readBounded(InputStream stream, int maxBytes) throws IOException {
    byte[] value = stream.readNBytes(maxBytes + 1);
    if (value.length > maxBytes) throw new IOException("文件超过大小限制");
    return value;
  }

  static boolean isFit(byte[] value) {
    if (value.length < 14
        || value[8] != '.'
        || value[9] != 'F'
        || value[10] != 'I'
        || value[11] != 'T') return false;
    int header = value[0] & 255;
    if (header < 12 || header > 64 || header > value.length - 2) return false;
    long data =
        (value[4] & 255L)
            | ((value[5] & 255L) << 8)
            | ((value[6] & 255L) << 16)
            | ((value[7] & 255L) << 24);
    return header + data + 2 == value.length;
  }

  static byte[] extractFit(byte[] bytes) throws IOException {
    if (isFit(bytes)) return bytes;
    if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') return null;
    byte[] found = null;
    int entries = 0;
    long totalExpanded = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (++entries > 8) return null;
        if (entry.isDirectory()) {
          if (readBounded(zip, 0).length != 0) return null;
          continue;
        }
        byte[] value = readBounded(zip, MAX_FIT_BYTES);
        totalExpanded += value.length;
        if (totalExpanded > MAX_FIT_BYTES) return null;
        if (isFit(value)) {
          if (found != null) return null;
          found = value;
        } else return null;
      }
    }
    return found;
  }

  static byte[] zip(byte[] fit, String fileName) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry(safeFileName(fileName)));
      zip.write(fit);
      zip.closeEntry();
    }
    return out.toByteArray();
  }

  static String safeFileName(String name) {
    String safe = name == null ? "activity.fit" : name.replace('\\', '/');
    safe = safe.substring(safe.lastIndexOf('/') + 1).replaceAll("[^\\p{L}\\p{N}._-]", "_");
    if (safe.isBlank()) safe = "activity.fit";
    if (safe.length() > 100) safe = safe.substring(0, 100);
    if (!safe.toLowerCase(Locale.ROOT).endsWith(".fit")) safe += ".fit";
    return safe;
  }

  static String hash(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException("缺少标准摘要算法", e);
    }
  }

  static String text(JsonNode root, String... keys) {
    for (String key : keys) {
      JsonNode node = root.path(key);
      if (node.isValueNode() && !node.isNull() && !node.asText().isBlank()) return node.asText();
    }
    return null;
  }

  static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  static Long startTime(JsonNode root, String... keys) {
    String raw = text(root, keys);
    if (raw == null) return null;
    try {
      long value = Long.parseLong(raw);
      return value <= 9_999_999_999L ? value * 1000 : value;
    } catch (RuntimeException ignored) {
    }
    try {
      return Instant.parse(raw).toEpochMilli();
    } catch (RuntimeException ignored) {
    }
    try {
      return OffsetDateTime.parse(raw).toInstant().toEpochMilli();
    } catch (RuntimeException ignored) {
    }
    try {
      return LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
          .atZone(CHINA)
          .toInstant()
          .toEpochMilli();
    } catch (RuntimeException ignored) {
    }
    try {
      return LocalDate.parse(raw.replace('.', '-')).atStartOfDay(CHINA).toInstant().toEpochMilli();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  static int pageNumber(String cursor) {
    try {
      return Math.max(1, Math.min(10_000, Integer.parseInt(cursor)));
    } catch (RuntimeException ignored) {
      return 1;
    }
  }

  static Double distance(JsonNode root, String key) {
    double value = root.path(key).asDouble(0);
    return value > 0 ? value : null;
  }

  static <T> ConnectorResult<T> fail(ConnectorResult.Status status, String code, String message) {
    return ConnectorResult.failure(status, code, message);
  }

  static <T> ConnectorResult<T> needsLogin() {
    return fail(REQUIRES_LOGIN, "AUTH_EXPIRED", "平台登录已失效，请重新登录");
  }

  static <T> ConnectorResult<T> statusFailure(int status, String what) {
    return fail(
        status == 408 || status == 429 || status >= 500 ? RETRYABLE_FAILURE : PERMANENT_FAILURE,
        "HTTP_" + status,
        what + "请求失败，请稍后重试或在平台网页确认");
  }
}
