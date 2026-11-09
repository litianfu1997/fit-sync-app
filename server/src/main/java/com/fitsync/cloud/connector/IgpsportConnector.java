package com.fitsync.cloud.connector;

import static com.fitsync.cloud.connector.ConnectorResult.Status.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** iGPSPORT 中国站私有网页协议；接口变化时返回失败，不能视作已成功同步。 */
public final class IgpsportConnector extends ConnectorHttpSupport {
  private static final String ACTIVITY = "/service/web-gateway/web-analyze/activity/";
  private static final int PAGE_SIZE = 20;

  public IgpsportConnector() {
    this(defaultClient(), URI.create("https://prod.zh.igpsport.com"), Clock.systemUTC());
  }

  public IgpsportConnector(HttpClient client, URI apiBase, Clock clock) {
    super(
        client,
        apiBase,
        "https://app.igpsport.cn",
        Set.of("igp-zh.oss-cn-hangzhou.aliyuncs.com"),
        clock);
  }

  @Override
  public String platform() {
    return "IGPSPORT";
  }

  @Override
  void authenticate(HttpRequest.Builder request, Session session) {
    if (!blank(session.accessToken()))
      request.header("Authorization", "Bearer " + session.accessToken());
  }

  @Override
  boolean businessSuccess(JsonNode root) {
    return root.has("code") && root.path("code").asInt(-1) == 0;
  }

  @Override
  String businessCode(JsonNode root) {
    return text(root, "code");
  }

  @Override
  public ConnectorResult<Session> login(
      String username, String password, boolean storeCredentials) {
    if (blank(username) || blank(password))
      return fail(PERMANENT_FAILURE, "CREDENTIALS_REQUIRED", "请输入 iGPSPORT 账号和密码");
    ConnectorResult<JsonNode> result =
        loginRequest(
            "/service/auth/account/login",
            Map.of("appId", "igpsport-web", "username", username, "password", password));
    if (result.status() != SUCCESS) return result.castFailure();
    JsonNode data = result.value().path("data");
    String token = text(data, "access_token");
    if (blank(token))
      return fail(REQUIRES_USER_ACTION, "LOGIN_PROTOCOL_CHANGED", "iGPSPORT 未返回有效登录信息，请在平台网页检查账号");
    String memberId = memberId(token);
    if (blank(memberId)) memberId = text(data, "memberId", "memberid");
    return ConnectorResult.success(
        new Session(token, memberId, username, password, storeCredentials));
  }

  @Override
  public ConnectorResult<ActivityPage> listActivities(
      Session session, long sinceEpochMs, String cursor) {
    int page = pageNumber(cursor);
    ConnectorResult<JsonNode> result =
        call(
            session,
            "GET",
            api(
                ACTIVITY
                    + "queryMyActivity?reqType=1&sportType=0&pageNo="
                    + page
                    + "&pageSize="
                    + PAGE_SIZE
                    + "&sort=1"),
            null,
            null);
    if (result.status() != SUCCESS) return result.castFailure();
    JsonNode rows = result.value().path("data").path("rows");
    if (!rows.isArray())
      return fail(RETRYABLE_FAILURE, "INVALID_ACTIVITY_LIST", "iGPSPORT 活动列表格式已变化");
    List<RemoteActivity> items = new ArrayList<>();
    int validIds = 0;
    for (JsonNode row : rows) {
      String id = text(row, "rideId", "idStr", "id");
      if (blank(id)) continue;
      validIds++;
      Long start = startTime(row, "startTime", "rideStartTime", "beginTime", "createTime");
      if (start != null && start < sinceEpochMs) continue;
      String url = fitUrl(row);
      Map<String, String> metadata = new HashMap<>();
      String sport = text(row, "exerciseType");
      if (sport != null) metadata.put("exercise_type", sport);
      if (url != null) metadata.put("fit_url", url);
      items.add(
          new RemoteActivity(
              id, start, text(row, "title", "name"), distance(row, "rideDistance"), url, metadata));
    }
    if (!rows.isEmpty() && validIds == 0)
      return fail(RETRYABLE_FAILURE, "MISSING_ACTIVITY_IDS", "iGPSPORT 活动缺少真实 ID");
    return ConnectorResult.success(
        new ActivityPage(
            items, Integer.toString(page + 1), rows.size() >= PAGE_SIZE && !items.isEmpty()));
  }

  @Override
  public ConnectorResult<FitDownload> downloadFit(Session session, RemoteActivity activity) {
    if (activity == null || blank(activity.remoteActivityId()))
      return fail(PERMANENT_FAILURE, "ACTIVITY_REQUIRED", "活动信息不完整");
    String url = activity.downloadUrl();
    if (blank(url)) url = activity.metadata().get("fit_url");
    if (blank(url)) {
      ConnectorResult<JsonNode> detail =
          call(
              session,
              "GET",
              api(ACTIVITY + "queryActivityDetail/" + q(activity.remoteActivityId())),
              null,
              null);
      if (detail.status() != SUCCESS) return detail.castFailure();
      url = fitUrl(detail.value().path("data"));
      if (blank(url)) url = fitUrl(detail.value());
    }
    if (blank(url)) return fail(PERMANENT_FAILURE, "NO_ORIGINAL_FILE", "iGPSPORT 未返回该活动的 FIT 下载地址");
    return downloadObject(url, "igpsport_" + activity.remoteActivityId() + ".fit");
  }

  @Override
  public ConnectorResult<UploadResult> uploadFit(
      Session session, byte[] fitBytes, String fileName) {
    ConnectorResult<Void> valid = validateUpload(fitBytes);
    if (valid.status() != SUCCESS) return valid.castFailure();
    ConnectorResult<JsonNode> signed =
        call(
            session,
            "GET",
            api("/service/sportg/third-party-server/oss/getSignedUrl?fileExtension=.fit"),
            null,
            null);
    if (signed.status() != SUCCESS) return signed.castFailure();
    JsonNode data = signed.value().path("data");
    String ossId = text(data, "ossId"), signedUrl = text(data, "signedUrl");
    if (blank(ossId) || blank(signedUrl))
      return fail(PERMANENT_FAILURE, "SIGN_URL_MISSING", "iGPSPORT 未返回有效文件上传凭证");
    if (!trustedObjectUrl(signedUrl))
      return fail(PERMANENT_FAILURE, "UNTRUSTED_UPLOAD_HOST", "iGPSPORT 文件上传地址不在 HTTPS 白名单内");
    try {
      // 与网页保持一致，不发送 Content-Type，否则会破坏 OSS 预签名。
      WireResponse uploaded =
          send(
              HttpRequest.newBuilder(URI.create(signedUrl))
                  .timeout(java.time.Duration.ofSeconds(90))
                  .PUT(HttpRequest.BodyPublishers.ofByteArray(fitBytes))
                  .build(),
              MAX_JSON_BYTES);
      if (uploaded.status() < 200 || uploaded.status() >= 300)
        return fail(RETRYABLE_FAILURE, "OSS_HTTP_" + uploaded.status(), "iGPSPORT 文件服务暂时无法接收上传");
      byte[] create =
          JSON.writeValueAsBytes(Map.of("fileName", safeFileName(fileName), "ossName", ossId));
      String confirmation = confirmationToken(session, fitBytes, ossId);
      return submit(
          session,
          api(ACTIVITY + "uploadByOss"),
          create,
          "application/json;charset=UTF-8",
          confirmation);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return fail(RETRYABLE_FAILURE, "INTERRUPTED", "iGPSPORT 文件上传被中断，请稍后重试");
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "OSS_UPLOAD_FAILED", "iGPSPORT 文件上传暂时失败");
    }
  }

  private static String fitUrl(JsonNode root) {
    return text(
        root, "fitUrl", "fitOssPath", "fit_url", "fileUrl", "originalFitUrl", "downloadUrl");
  }

  private static String memberId(String token) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length < 2 || parts[1].length() > 32768) return null;
      // 仅提取平台签发 JWT 的账号标识，不将此处解码用于鉴权。
      return text(JSON.readTree(Base64.getUrlDecoder().decode(parts[1])), "memberid", "memberId");
    } catch (Exception e) {
      return null;
    }
  }
}
