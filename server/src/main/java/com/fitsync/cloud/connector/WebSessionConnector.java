package com.fitsync.cloud.connector;

import static com.fitsync.cloud.connector.ConnectorResult.Status.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 网页会话连接器的公共边界：显式导入、受限请求、绝不自动重放已受理的上传。 */
abstract class WebSessionConnector extends ConnectorHttpSupport {
  WebSessionConnector(HttpClient client, URI apiBase, String origin, Set<String> objectHosts, Clock clock) {
    super(client, apiBase, origin, objectHosts, clock);
  }

  @Override
  public ConnectorResult<Session> login(
      String username, String password, boolean storeCredentials) {
    return fail(REQUIRES_USER_ACTION, "WEB_SESSION_REQUIRED", "该平台请使用网页会话连接，不需要提交密码");
  }

  /** 若用户授权托管了账号密码，在会话失效时自动重登并原地更新当前会话。 */
  protected final boolean reloginIfPossible(Session session) {
    if (session == null
        || !session.storeCredentials()
        || blank(session.username())
        || blank(session.password())) return false;
    ConnectorResult<Session> renewed = login(session.username(), session.password(), true);
    if (renewed.status() != SUCCESS || renewed.value() == null) return false;
    session.updateFrom(renewed.value());
    return true;
  }

  /** 校验会话并做一次只读探活；成功后才允许创建云端账号。 */
  @Override
  public abstract ConnectorResult<Session> connectWeb(Map<String, String> input);

  protected final ConnectorResult<Session> invalidSession(String message) {
    return fail(PERMANENT_FAILURE, "SESSION_INVALID", message);
  }

  protected final ConnectorResult<Session> webSession(
      String token, String uid, Map<String, String> attributes) {
    return ConnectorResult.success(new Session(token, uid, null, null, false, attributes));
  }

  protected static boolean validFields(Map<String, String> fields) {
    if (fields == null || fields.size() > 16) return false;
    long total = 0;
    for (var field : fields.entrySet()) {
      String key = field.getKey(), value = field.getValue();
      if (key == null || !key.matches("[A-Za-z][A-Za-z0-9]{0,31}") || value == null) return false;
      if (value.length() > 65_536 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return false;
      total += value.length();
    }
    return total <= 131_072;
  }

  protected static String required(Map<String, String> fields, String key) {
    String value = fields.get(key);
    return value == null || value.isBlank() ? null : value.trim();
  }

  protected static String optional(Map<String, String> fields, String key) {
    return required(fields, key);
  }

  protected final String cookie(Session session) {
    return session == null ? null : session.attribute("cookie");
  }

  protected final String cookie(Session session, String raw) {
    return cookie(raw, session == null ? null : session.accessToken());
  }

  protected final HttpRequest.Builder webRequest(URI uri, Session session, String cookie) {
    HttpRequest.Builder builder = request(uri, session)
        .header("Accept-Encoding", "gzip")
        .header("User-Agent", browserAgent(session));
    if (!blank(cookie)) builder.header("Cookie", cookie);
    return builder;
  }

  protected final String browserAgent(Session session) {
    String agent = session == null ? null : session.attribute("userAgent");
    return blank(agent)
        ? "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        : agent;
  }

  /** 网页 JSON 不套用 COROS 的业务码规则；每个平台在拿到 JSON 后自行判定。 */
  protected final ConnectorResult<JsonNode> json(WireResponse response, String what) {
    if (response.status() == 401 || response.status() == 403) return needsLogin();
    if (response.status() >= 300 && response.status() < 400) {
      String location = response.header("Location");
      if (location != null && location.toLowerCase().contains("login")) return needsLogin();
      return fail(RETRYABLE_FAILURE, "UNEXPECTED_REDIRECT", what + "发生了未预期跳转");
    }
    if (response.status() == 408 || response.status() == 429 || response.status() >= 500)
      return statusFailure(response.status(), what);
    if (response.status() < 200 || response.status() >= 300)
      return fail(PERMANENT_FAILURE, "HTTP_" + response.status(), what + "请求失败");
    try {
      JsonNode value = JSON.readTree(response.bytes());
      if (value == null || value.isMissingNode())
        return fail(RETRYABLE_FAILURE, "INVALID_RESPONSE", what + "响应格式异常");
      return ConnectorResult.success(value);
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "INVALID_RESPONSE", what + "响应格式异常");
    }
  }

  protected final ConnectorResult<UploadResult> uploadNetworkFailure() {
    return fail(RETRYABLE_FAILURE, "UPLOAD_NETWORK", "上传请求未完成，将在确认远端状态后继续");
  }

  /**
   * POST 请求已经发出后，网络错误、限流或协议不明确都不能证明平台未接收文件。
   * 此时返回按文件哈希确认的 PROCESSING，让任务状态机只查远端、不重新上传。
   */
  protected final ConnectorResult<UploadResult> uncertainUpload(byte[] fit, String hint) {
    return ConnectorResult.processing(pendingToken(fit, hint));
  }

  /** 发送受控网页请求，保留状态码、响应头和有限正文供各平台分类。 */
  protected final ConnectorResult<WireResponse> sendWeb(HttpRequest request, String what) {
    try {
      return ConnectorResult.success(send(request, MAX_FIT_BYTES));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return fail(RETRYABLE_FAILURE, "INTERRUPTED", what + "被中断，请稍后重试");
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "NETWORK", what + "暂时无法连接，请稍后重试");
    }
  }

  /** 接收响应后刷新该账号 Cookie；不会把新值返回给客户端或写入日志。 */
  protected final void captureCookies(Session session, WireResponse response) {
    if (session == null || response.cookies().isEmpty()) return;
    String merged = mergeCookies(cookie(session), response.cookies());
    if (merged != null) session.setAttribute("cookie", merged);
  }

  protected static String mergeCookies(String current, List<String> setCookies) {
    Map<String, String> values = new LinkedHashMap<>();
    if (current != null) {
      for (String part : current.split(";")) {
        String[] pair = part.trim().split("=", 2);
        if (pair.length == 2 && !pair[0].isBlank()) values.put(pair[0].trim(), pair[1].trim());
      }
    }
    for (String header : setCookies) {
      String[] pair = header.split(";", 2)[0].split("=", 2);
      if (pair.length != 2 || pair[0].isBlank()) continue;
      if (pair[1].isBlank() || header.matches("(?i).*Max-Age=0(?:;|$).*"))
        values.remove(pair[0].trim());
      else values.put(pair[0].trim(), pair[1].trim());
    }
    if (values.isEmpty()) return null;
    StringBuilder result = new StringBuilder();
    values.forEach((key, value) -> {
      if (!result.isEmpty()) result.append("; ");
      result.append(key).append('=').append(value);
    });
    return result.toString();
  }

  /** 网页会话上传确认不依赖外部 UID，仍以文件 SHA-256 防错配。 */
  protected final String webConfirmation(byte[] fit, String hint) throws Exception {
    return encodeWebConfirmation(platform(), hash("SHA-256", fit), hint, clock.millis());
  }

  /**
   * 网页平台只返回“已受理”时的确认流程：分页检查最近活动，只下载少量候选并比对
   * SHA-256，命中才记录真实远端 ID；未命中则推进游标，绝不重新 POST 文件。
   */
  @Override
  public ConnectorResult<UploadResult> checkUpload(Session session, String confirmationToken) {
    try {
      if (confirmationToken == null || confirmationToken.length() > 4096)
        return invalidConfirmation();
      WebConfirmation token =
          JSON.readValue(
              new String(
                  java.util.Base64.getUrlDecoder().decode(confirmationToken),
                  java.nio.charset.StandardCharsets.UTF_8),
              WebConfirmation.class);
      if (!platform().equals(token.platform())
          || token.sha256() == null
          || !token.sha256().matches("[a-f0-9]{64}")
          || token.submittedAt() <= 0
          || token.submittedAt() > clock.millis() + 60_000)
        return invalidConfirmation();
      long age = clock.millis() - token.submittedAt();
      if (age > java.time.Duration.ofHours(24).toMillis()) {
        return fail(
            REQUIRES_USER_ACTION,
            "CONFIRMATION_REQUIRED",
            "上传尚未确认，请前往平台核对活动，避免重复投递");
      }
      String cursor = cursorFromHint(token.hint());
      ConnectorResult<ActivityPage> pageResult = listActivities(session, 0, cursor);
      if (pageResult.status() != SUCCESS) return pageResult.castFailure();
      int inspected = 0;
      for (RemoteActivity candidate : pageResult.value().items()) {
        if (inspected >= 5) break;
        inspected++;
        ConnectorResult<FitDownload> file = downloadFit(session, candidate);
        if (file.status() == REQUIRES_LOGIN) return file.castFailure();
        if (file.status() == SUCCESS && token.sha256().equals(hash("SHA-256", file.value().bytes())))
          return ConnectorResult.success(new UploadResult(candidate.remoteActivityId()));
      }
      String next =
          pageResult.value().hasMore() && pageResult.value().nextCursor() != null
              ? pageResult.value().nextCursor()
              : "1";
      return ConnectorResult.processing(
          encodeWebConfirmation(
              platform(), token.sha256(), next, token.submittedAt()));
    } catch (Exception e) {
      return invalidConfirmation();
    }
  }

  private String cursorFromHint(String hint) {
    if (hint == null || hint.isBlank()) return null;
    try {
      return Integer.toString(Math.max(1, Integer.parseInt(hint)));
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private ConnectorResult<UploadResult> invalidConfirmation() {
    return fail(REQUIRES_USER_ACTION, "INVALID_CONFIRMATION", "上传确认信息无效，请在平台核对活动状态");
  }

  private static String encodeWebConfirmation(
      String platform, String sha256, String hint, long submittedAt) throws Exception {
    return java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(JSON.writeValueAsString(new WebConfirmation(platform, sha256, hint, submittedAt))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  protected final String pendingToken(byte[] fit, String hint) {
    try {
      return webConfirmation(fit, hint);
    } catch (Exception e) {
      throw new IllegalStateException("无法生成上传确认信息", e);
    }
  }

  record WebConfirmation(String platform, String sha256, String hint, long submittedAt) {}
}
