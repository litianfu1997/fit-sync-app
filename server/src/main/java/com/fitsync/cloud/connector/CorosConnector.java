package com.fitsync.cloud.connector;

import static com.fitsync.cloud.connector.ConnectorResult.Status.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** COROS 中国站网页协议，STS 凭据只用于当前上传，绝不写入账号状态。 */
public final class CorosConnector extends ConnectorHttpSupport {
  private static final int PAGE_SIZE = 200;
  private static final String OSS_SALT = "9y78gpoERW4lBNYL";
  private final URI stsUrl;

  public CorosConnector() {
    this(
        defaultClient(),
        URI.create("https://teamcnapi.coros.com"),
        URI.create("https://faq.coros.com/openapi/oss/sts"),
        Clock.systemUTC());
  }

  public CorosConnector(HttpClient client, URI apiBase, URI stsUrl, Clock clock) {
    super(
        client,
        apiBase,
        "https://teamcnapi.coros.com",
        Set.of(
            "coros-oss.oss-cn-beijing.aliyuncs.com", "coros-oss.oss-cn-hangzhou.aliyuncs.com",
            "coros-oss.oss-accelerate.aliyuncs.com",
                "coros-oss.oss-accelerate-overseas.aliyuncs.com"),
        clock);
    this.stsUrl = stsUrl;
  }

  @Override
  public String platform() {
    return "COROS";
  }

  @Override
  void authenticate(HttpRequest.Builder request, Session session) {
    if (!blank(session.accessToken())) request.header("accesstoken", session.accessToken());
  }

  @Override
  boolean businessSuccess(JsonNode root) {
    return "0000".equals(text(root, "result"));
  }

  @Override
  String businessCode(JsonNode root) {
    return text(root, "result");
  }

  @Override
  public ConnectorResult<Session> login(
      String username, String password, boolean storeCredentials) {
    if (blank(username) || blank(password))
      return fail(PERMANENT_FAILURE, "CREDENTIALS_REQUIRED", "请输入 COROS 账号和密码");
    ConnectorResult<JsonNode> result =
        loginRequest(
            "/account/login",
            Map.of(
                "account",
                username,
                "accountType",
                2,
                "pwd",
                hash("MD5", password.getBytes(StandardCharsets.UTF_8))));
    if (result.status() != SUCCESS) return result.castFailure();
    JsonNode data = result.value().path("data");
    String token = text(data, "accessToken"), userId = text(data, "userId");
    if (blank(token) || blank(userId))
      return fail(REQUIRES_USER_ACTION, "LOGIN_PROTOCOL_CHANGED", "COROS 未返回有效登录信息，请在平台网页确认账号");
    if (data.path("regionId").asInt(-1) != 2)
      return fail(REQUIRES_USER_ACTION, "REGION_UNSUPPORTED", "当前仅支持 COROS 中国站账号");
    return ConnectorResult.success(
        new Session(token, userId, username, password, storeCredentials));
  }

  @Override
  public ConnectorResult<ActivityPage> listActivities(
      Session session, long sinceEpochMs, String cursor) {
    int page = pageNumber(cursor);
    ConnectorResult<JsonNode> result =
        call(
            session,
            "GET",
            api("/activity/query?size=" + PAGE_SIZE + "&pageNumber=" + page),
            null,
            null);
    if (result.status() != SUCCESS) return result.castFailure();
    JsonNode data = result.value().path("data"), rows = data.path("dataList");
    if (!rows.isArray()) return fail(RETRYABLE_FAILURE, "INVALID_ACTIVITY_LIST", "COROS 活动列表格式已变化");
    List<RemoteActivity> items = new ArrayList<>();
    int validIds = 0;
    for (JsonNode row : rows) {
      String id = text(row, "labelId");
      if (blank(id)) continue;
      validIds++;
      Long start = startTime(row, "startTime", "timestamp", "startTimeStamp", "createTime");
      if (start == null) {
        String date = text(row, "date");
        try {
          start =
              LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE)
                  .atStartOfDay(CHINA)
                  .toInstant()
                  .toEpochMilli();
        } catch (RuntimeException ignored) {
        }
      }
      if (start != null && start < sinceEpochMs) continue;
      String sport = text(row, "sportType");
      items.add(
          new RemoteActivity(
              id,
              start,
              text(row, "name"),
              distance(row, "distance"),
              null,
              sport == null ? Map.of() : Map.of("sport_type", sport)));
    }
    if (!rows.isEmpty() && validIds == 0)
      return fail(RETRYABLE_FAILURE, "MISSING_ACTIVITY_IDS", "COROS 活动缺少真实 ID");
    int totalPages = data.path("totalPage").asInt(Integer.MAX_VALUE);
    return ConnectorResult.success(
        new ActivityPage(items, Integer.toString(page + 1), !items.isEmpty() && page < totalPages));
  }

  @Override
  public ConnectorResult<FitDownload> downloadFit(Session session, RemoteActivity activity) {
    if (activity == null || blank(activity.remoteActivityId()))
      return fail(PERMANENT_FAILURE, "ACTIVITY_REQUIRED", "活动信息不完整");
    String sport = activity.metadata().get("sport_type");
    if (blank(sport)) return fail(PERMANENT_FAILURE, "SPORT_TYPE_REQUIRED", "COROS 活动缺少下载所需的运动类型");
    if ("65535".equals(sport)) sport = "100";
    ConnectorResult<JsonNode> result =
        call(
            session,
            "POST",
            api(
                "/activity/detail/download?labelId="
                    + q(activity.remoteActivityId())
                    + "&sportType="
                    + q(sport)
                    + "&fileType=4"),
            new byte[0],
            null);
    if (result.status() != SUCCESS) return result.castFailure();
    String url = text(result.value().path("data"), "fileUrl");
    if (blank(url)) return fail(PERMANENT_FAILURE, "NO_ORIGINAL_FILE", "COROS 未返回该活动的 FIT 下载地址");
    return downloadObject(url, "coros_" + activity.remoteActivityId() + ".fit");
  }

  @Override
  public ConnectorResult<UploadResult> uploadFit(
      Session session, byte[] fitBytes, String fileName) {
    ConnectorResult<Void> valid = validateUpload(fitBytes);
    if (valid.status() != SUCCESS) return valid.castFailure();
    if (session == null || blank(session.externalUserId())) return needsLogin();
    ConnectorResult<Sts> stsResult = fetchSts();
    if (stsResult.status() != SUCCESS) return stsResult.castFailure();
    Sts sts = stsResult.value();
    try {
      String name = safeFileName(fileName), md5 = hash("MD5", fitBytes);
      String key = "fit_zip/" + q(session.externalUserId()) + "/" + md5 + ".zip";
      byte[] zipped = zip(fitBytes, name);
      String date =
          DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
              .withZone(ZoneOffset.UTC)
              .format(clock.instant());
      String toSign =
          "PUT\n\napplication/octet-stream\n"
              + date
              + "\nx-oss-security-token:"
              + sts.securityToken
              + "\n/"
              + sts.bucket
              + "/"
              + key;
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(sts.accessKeySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
      String authorization =
          "OSS "
              + sts.accessKeyId
              + ":"
              + Base64.getEncoder()
                  .encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
      String url = "https://" + sts.bucket + "." + sts.region + ".aliyuncs.com/" + key;
      if (!trustedObjectUrl(url))
        return fail(PERMANENT_FAILURE, "UNTRUSTED_UPLOAD_HOST", "COROS 上传凭证指向非预期的存储服务");
      WireResponse uploaded =
          send(
              HttpRequest.newBuilder(URI.create(url))
                  .timeout(java.time.Duration.ofSeconds(90))
                  .header("Content-Type", "application/octet-stream")
                  .header("Date", date)
                  .header("Authorization", authorization)
                  .header("x-oss-security-token", sts.securityToken)
                  .PUT(HttpRequest.BodyPublishers.ofByteArray(zipped))
                  .build(),
              MAX_JSON_BYTES);
      if (uploaded.status() < 200 || uploaded.status() >= 300)
        return fail(RETRYABLE_FAILURE, "OSS_HTTP_" + uploaded.status(), "COROS 文件服务暂时无法接收上传");
      String parameters =
          JSON.writeValueAsString(
              Map.of(
                  "source",
                  1,
                  "timezone",
                  32,
                  "bucket",
                  sts.bucket,
                  "md5",
                  md5,
                  "size",
                  zipped.length,
                  "object",
                  key,
                  "serviceName",
                  "oss",
                  "oriFileName",
                  name));
      String boundary = "FitSync" + java.util.UUID.randomUUID().toString().replace("-", "");
      byte[] body =
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\"jsonParameter\"\r\n\r\n"
                  + parameters
                  + "\r\n--"
                  + boundary
                  + "--\r\n")
              .getBytes(StandardCharsets.UTF_8);
      String confirmation = confirmationToken(session, fitBytes, md5);
      return submit(
          session,
          api("/activity/fit/import"),
          body,
          "multipart/form-data; boundary=" + boundary,
          confirmation);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return fail(RETRYABLE_FAILURE, "INTERRUPTED", "COROS 文件上传被中断，请稍后重试");
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "OSS_UPLOAD_FAILED", "COROS 文件上传暂时失败");
    }
  }

  private ConnectorResult<Sts> fetchSts() {
    try {
      URI uri =
          URI.create(
              stsUrl.toString()
                  + "?bucket=coros-oss&service=aliyun&app_id=1660188068672619112&sign=9AD4AA35AAFEE6BB1E847A76848D58DF&v=2");
      WireResponse response =
          send(
              HttpRequest.newBuilder(uri)
                  .timeout(java.time.Duration.ofSeconds(30))
                  .header("Accept", "application/json")
                  .GET()
                  .build(),
              MAX_JSON_BYTES);
      if (response.status() < 200 || response.status() >= 300)
        return statusFailure(response.status(), "COROS 上传凭证");
      JsonNode root = JSON.readTree(response.bytes());
      String encoded = text(root.path("data"), "credentials");
      if (root.path("code").asInt(-1) != 200 || blank(encoded))
        return fail(PERMANENT_FAILURE, "STS_UNAVAILABLE", "COROS 上传凭证接口发生变化");
      JsonNode value = JSON.readTree(Base64.getDecoder().decode(encoded.replace(OSS_SALT, "")));
      String id = text(value, "AccessKeyId"),
          secret = text(value, "AccessKeySecret"),
          token = text(value, "SecurityToken");
      String bucket = text(value, "Bucket"), region = text(value, "Region");
      if (blank(bucket)) bucket = "coros-oss";
      if (blank(region)) region = "oss-cn-beijing";
      if (blank(id)
          || blank(secret)
          || blank(token)
          || !"coros-oss".equals(bucket)
          || !Set.of("oss-cn-beijing", "oss-cn-hangzhou").contains(region)) {
        return fail(PERMANENT_FAILURE, "INVALID_STS", "COROS 未返回安全有效的上传凭证");
      }
      return ConnectorResult.success(new Sts(id, secret, token, bucket, region));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return fail(RETRYABLE_FAILURE, "INTERRUPTED", "COROS 上传凭证请求被中断");
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "STS_FAILED", "暂时无法获取 COROS 上传凭证");
    }
  }

  private record Sts(
      String accessKeyId,
      String accessKeySecret,
      String securityToken,
      String bucket,
      String region) {}
}
