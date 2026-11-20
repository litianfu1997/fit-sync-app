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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** COROS 中国站网页协议，STS 凭据只用于当前上传，绝不写入账号状态。 */
public final class CorosConnector extends ConnectorHttpSupport {
  private static final int PAGE_SIZE = 200;
  private static final String OSS_SALT = "9y78gpoERW4lBNYL";
  private static final String DEFAULT_STS_ORIGIN = "https://trainingcn.coros.com";
  private static final String COOKIE_TOKEN = "CPL-coros-token";
  private static final String COOKIE_REGION = "CPL-coros-region";
  private static final Pattern OSS_BUCKET_PATTERN = Pattern.compile("^coros[a-z0-9-]{0,58}$");
  private static final Pattern OSS_REGION_PATTERN = Pattern.compile("^oss-[a-z0-9-]{2,32}$");
  private static final Pattern COROS_OSS_HOST_PATTERN =
      Pattern.compile("^coros[a-z0-9-]{0,58}\\.oss-[a-z0-9-]{2,32}\\.aliyuncs\\.com$");
  private final URI stsUrl;

  public CorosConnector() {
    this(
        defaultClient(),
        URI.create("https://teamcnapi.coros.com"),
        URI.create("https://trainingcn.coros.com/api/proxy/oss/sts"),
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
  protected boolean trustedObjectUrl(String url) {
    if (super.trustedObjectUrl(url)) return true;
    try {
      URI uri = URI.create(url);
      String host = uri.getHost();
      return "https".equalsIgnoreCase(uri.getScheme())
          && uri.getUserInfo() == null
          && uri.getFragment() == null
          && (uri.getPort() == -1 || uri.getPort() == 443)
          && host != null
          && COROS_OSS_HOST_PATTERN.matcher(host.toLowerCase(Locale.ROOT)).matches();
    } catch (RuntimeException e) {
      return false;
    }
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
      ParsedStartTime parsedStart =
          startTimeDetail(row, "startTime", "timestamp", "startTimeStamp", "createTime");
      if (parsedStart == null) {
        String date = text(row, "date");
        try {
          if (date != null) {
            parsedStart =
                new ParsedStartTime(
                    LocalDate.parse(date.trim(), DateTimeFormatter.BASIC_ISO_DATE)
                        .atStartOfDay(CHINA)
                        .toInstant()
                        .toEpochMilli(),
                    true);
          }
        } catch (RuntimeException ignored) {
        }
      }
      if (isBeforeSince(parsedStart, sinceEpochMs)) continue;
      Long start = parsedStart == null ? null : parsedStart.epochMs();
      String sport = text(row, "sportType");
      Map<String, String> metadata = new HashMap<>();
      if (sport != null) metadata.put("sport_type", sport);
      if (parsedStart != null && parsedStart.dateOnly()) {
        metadata.put("start_time_precision", "DATE");
      }
      items.add(
          new RemoteActivity(
              id,
              start,
              text(row, "name"),
              distance(row, "distance"),
              null,
              metadata));
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
    if (session == null || blank(session.externalUserId()) || blank(session.accessToken()))
      return needsLogin();
    ConnectorResult<Sts> stsResult = fetchSts(session);
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
                  sts.serviceName,
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

  private ConnectorResult<Sts> fetchSts(Session session) {
    ConnectorResult<Sts> first = fetchStsOnce(session);
    if (first.status() != REQUIRES_LOGIN
        || session == null
        || !session.storeCredentials()
        || blank(session.username())
        || blank(session.password())) return first;
    ConnectorResult<Session> renewed = login(session.username(), session.password(), true);
    if (renewed.status() != SUCCESS) return renewed.castFailure();
    session.updateFrom(renewed.value());
    return fetchStsOnce(session);
  }

  private ConnectorResult<Sts> fetchStsOnce(Session session) {
    if (session == null || blank(session.accessToken())) return needsLogin();
    try {
      String authority = stsUrl.getRawAuthority();
      String stsOrigin =
          blank(stsUrl.getScheme()) || blank(authority)
              ? DEFAULT_STS_ORIGIN
              : stsUrl.getScheme() + "://" + authority;
      WireResponse response =
          send(
              HttpRequest.newBuilder(stsUrl)
                  .timeout(java.time.Duration.ofSeconds(30))
                  .header("Accept", "application/json, text/plain, */*")
                  .header("Origin", stsOrigin)
                  .header("Referer", stsOrigin + "/")
                  .header("User-Agent", USER_AGENT)
                  .header(
                      "Cookie",
                      COOKIE_TOKEN + "=" + session.accessToken() + "; " + COOKIE_REGION + "=2")
                  .GET()
                  .build(),
              MAX_JSON_BYTES);
      if (response.status() == 401 || response.status() == 403) return needsLogin();
      if (response.status() < 200 || response.status() >= 300)
        return statusFailure(response.status(), "COROS 上传凭证");
      JsonNode root = JSON.readTree(response.bytes());
      int code = root.path("code").asInt(-1);
      if (code == 401 || code == 403) return needsLogin();
      JsonNode data = root.path("data");
      String encoded = text(data, "credentials");
      if (code != 200 || blank(encoded))
        return fail(PERMANENT_FAILURE, "STS_UNAVAILABLE", "COROS 上传凭证接口发生变化");
      String rawService = text(data, "service");
      String serviceName = blank(rawService) ? "aliyun" : rawService.trim().toLowerCase(Locale.ROOT);
      if (!Set.of("aliyun", "oss").contains(serviceName))
        return fail(PERMANENT_FAILURE, "UNSUPPORTED_STS_SERVICE", "COROS 返回了暂不支持的存储服务类型");
      JsonNode value = JSON.readTree(Base64.getDecoder().decode(encoded.replace(OSS_SALT, "")));
      String id = text(value, "AccessKeyId"),
          secret = text(value, "AccessKeySecret"),
          token = text(value, "SecurityToken");
      String bucket = text(value, "Bucket"), region = text(value, "Region");
      bucket = blank(bucket) ? "coros-oss" : bucket.trim().toLowerCase(Locale.ROOT);
      region = blank(region) ? "oss-cn-beijing" : region.trim().toLowerCase(Locale.ROOT);
      if (region.endsWith(".aliyuncs.com")) {
        region = region.substring(0, region.length() - ".aliyuncs.com".length());
      }
      if (blank(id)
          || blank(secret)
          || blank(token)
          || !OSS_BUCKET_PATTERN.matcher(bucket).matches()
          || !OSS_REGION_PATTERN.matcher(region).matches()) {
        return fail(PERMANENT_FAILURE, "INVALID_STS", "COROS 未返回安全有效的上传凭证");
      }
      return ConnectorResult.success(new Sts(id, secret, token, bucket, region, serviceName));
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
      String region,
      String serviceName) {}
}
