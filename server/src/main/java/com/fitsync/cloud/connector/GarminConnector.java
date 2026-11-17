package com.fitsync.cloud.connector;

import static com.fitsync.cloud.connector.ConnectorResult.Status.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Garmin Connect 中国站账号密码直接登录、网页会话、gc-api 双向同步及 SSO 静默续期。 */
public final class GarminConnector extends WebSessionConnector {
  private static final int PAGE_SIZE = 20;
  private static final String BASE = "https://connect.garmin.cn";
  private static final String GC_API = "https://connect.garmin.cn/gc-api";
  private static final Set<String> GARMIN_HOSTS =
      Set.of("connect.garmin.cn", "sso.garmin.cn", "www.garmin.cn");
  private static final int MAX_SSO_HOPS = 8;
  private static final Pattern CSRF_INPUT = Pattern.compile(
      "(?is)<input\\b[^>]*name=[\"']_csrf[\"'][^>]*value=[\"']([^\"']+)[\"']");
  private static final Pattern CSRF_INPUT_REV = Pattern.compile(
      "(?is)<input\\b[^>]*value=[\"']([^\"']+)[\"'][^>]*name=[\"']_csrf[\"']");
  private static final Pattern TICKET_PARAM = Pattern.compile(
      "(?:[?&]ticket=|serviceTicketId[\"']?\\s*[:=]\\s*[\"'])(ST-[A-Za-z0-9._-]+)",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern TICKET_GENERIC = Pattern.compile("[?&]ticket=([^\"'&\\s<\\\\;)]+)");

  public GarminConnector() {
    this(defaultClient(), URI.create(BASE), Clock.systemUTC());
  }

  public GarminConnector(HttpClient client, URI apiBase, Clock clock) {
    super(client, apiBase, BASE, GARMIN_HOSTS, clock);
  }

  @Override
  public String platform() {
    return "GARMIN";
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
      return fail(PERMANENT_FAILURE, "CREDENTIALS_REQUIRED", "请输入 Garmin 中国站账号和密码");
    String account = username.trim();
    String service = apiBase.resolve("/app/").toString();
    URI ssoBase = "connect.garmin.cn".equalsIgnoreCase(apiBase.getHost())
        ? URI.create("https://sso.garmin.cn")
        : apiBase;
    URI signinUri = ssoBase.resolve(
        "/sso/signin?clientId=GarminConnect&service=" + q(service) + "&embed=false");
    HttpRequest pageReq = HttpRequest.newBuilder(signinUri)
        .timeout(Duration.ofSeconds(60))
        .header("User-Agent", browserAgent(null))
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .GET()
        .build();
    ConnectorResult<WireResponse> pageResult = sendWeb(pageReq, "Garmin 登录页");
    if (pageResult.status() != SUCCESS) return pageResult.castFailure();
    WireResponse pageWire = pageResult.value();
    if (pageWire.status() == 429 || pageWire.status() >= 500)
      return statusFailure(pageWire.status(), "Garmin 登录页");
    if (pageWire.status() < 200 || pageWire.status() >= 300)
      return fail(PERMANENT_FAILURE, "HTTP_" + pageWire.status(), "Garmin 登录页不可用");
    String ssoCookie = mergeCookies(null, pageWire.cookies());
    String pageHtml = new String(pageWire.bytes(), StandardCharsets.UTF_8);
    String formCsrf = extractFormCsrf(pageHtml);
    if (formCsrf == null) formCsrf = "";

    String form =
        "username=" + q(account)
            + "&password=" + q(password)
            + "&embed=false"
            + "&_csrf=" + q(formCsrf)
            + "&clientId=GarminConnect"
            + "&service=" + q(service)
            + "&locale=zh-CN";
    HttpRequest.Builder postBuilder = HttpRequest.newBuilder(signinUri)
        .timeout(Duration.ofSeconds(60))
        .header("User-Agent", browserAgent(null))
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Origin", ssoBase.getScheme() + "://" + ssoBase.getHost())
        .header("Referer", signinUri.toString())
        .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
    if (!blank(ssoCookie)) postBuilder.header("Cookie", ssoCookie);
    ConnectorResult<WireResponse> postResult = sendWeb(postBuilder.build(), "Garmin 登录提交");
    if (postResult.status() != SUCCESS) return postResult.castFailure();
    WireResponse postWire = postResult.value();
    ssoCookie = mergeCookies(ssoCookie, postWire.cookies());
    String postBody = new String(postWire.bytes(), StandardCharsets.UTF_8);
    String ticket = extractTicket(postWire.header("Location"), postBody);
    if (blank(ticket)) {
      if (postWire.status() == 429) return statusFailure(429, "Garmin 登录");
      String lower = postBody.toLowerCase(Locale.ROOT);
      if (lower.contains("mfa")
          || lower.contains("two-step")
          || lower.contains("two step")
          || lower.contains("verification")
          || lower.contains("security code"))
        return fail(REQUIRES_USER_ACTION, "GARMIN_MFA_REQUIRED", "该 Garmin 账号开启了两步验证，请先关闭两步验证后再连接");
      if (lower.contains("captcha") || lower.contains("cloudflare"))
        return fail(REQUIRES_USER_ACTION, "GARMIN_CAPTCHA", "Garmin 登录触发了人机验证，请稍后再试");
      return fail(PERMANENT_FAILURE, "GARMIN_AUTH_FAILED", "Garmin 中国站账号或密码错误");
    }

    String current = apiBase.resolve("/app/?ticket=" + q(ticket)).toString();
    String connectCookie = "";
    String jwt = null;
    String metaCsrf = null;
    Set<String> allowed = allowedHosts();
    for (int hop = 0; hop < MAX_SSO_HOPS; hop++) {
      URI uri;
      try {
        uri = URI.create(current);
      } catch (RuntimeException e) {
        break;
      }
      if (!allowedHost(uri, allowed)) break;
      boolean ssoHost = "sso.garmin.cn".equalsIgnoreCase(uri.getHost());
      HttpRequest.Builder stepBuilder = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(60))
          .header("User-Agent", browserAgent(null))
          .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
          .header("Sec-Fetch-Dest", "document")
          .header("Sec-Fetch-Mode", "navigate")
          .header("Sec-Fetch-Site", "same-site")
          .header("Upgrade-Insecure-Requests", "1")
          .header("Referer", signinUri.toString())
          .GET();
      String hopCookie = ssoHost ? ssoCookie : connectCookie;
      if (!blank(hopCookie)) stepBuilder.header("Cookie", hopCookie);
      ConnectorResult<WireResponse> stepResult = sendWeb(stepBuilder.build(), "Garmin 会话换发");
      if (stepResult.status() != SUCCESS) return stepResult.castFailure();
      WireResponse stepWire = stepResult.value();
      String merged = mergeCookies(hopCookie, stepWire.cookies());
      if (ssoHost) ssoCookie = merged == null ? "" : merged;
      else connectCookie = merged == null ? "" : merged;
      String stepJwt = cookie(mergeCookies(null, stepWire.cookies()), "JWT_WEB");
      if (stepJwt != null) jwt = stepJwt;
      String html = new String(stepWire.bytes(), StandardCharsets.UTF_8);
      String foundCsrf = extractCsrf(html);
      if (foundCsrf != null) metaCsrf = foundCsrf;
      String location = stepWire.header("Location");
      if (stepWire.status() >= 300 && stepWire.status() < 400 && location != null) {
        try {
          current = uri.resolve(location).toString();
        } catch (RuntimeException e) {
          break;
        }
        continue;
      }
      break;
    }

    if (jwt == null && !blank(connectCookie)) {
      jwt = cookie(connectCookie, "JWT_WEB");
    }
    Map<String, String> values = new LinkedHashMap<>();
    if (!blank(connectCookie)) values.put("cookie", connectCookie);
    if (!blank(ssoCookie)) values.put("ssoCookie", ssoCookie);
    if (!blank(metaCsrf)) values.put("csrf", metaCsrf);
    values.put("userAgent", browserAgent(null));
    Session session = new Session(jwt, null, account, password, storeCredentials, values);
    if (blank(jwt) && !blank(ssoCookie)) {
      if (blank(session.attribute("cookie"))) session.setAttribute("cookie", "");
      ConnectorResult<Session> refreshed = refreshSession(session);
      if (refreshed.status() == SUCCESS) {
        jwt = session.accessToken();
      }
    }
    if (blank(jwt)) {
      return fail(REQUIRES_USER_ACTION, "GARMIN_TICKET_EXCHANGE_FAILED", "Garmin 登录凭据换发未完成，请稍后重试");
    }
    return ConnectorResult.success(session);
  }

  private static String extractFormCsrf(String html) {
    Matcher m1 = CSRF_INPUT.matcher(html);
    if (m1.find()) return m1.group(1);
    Matcher m2 = CSRF_INPUT_REV.matcher(html);
    return m2.find() ? m2.group(1) : null;
  }

  private static String extractTicket(String location, String body) {
    if (location != null) {
      Matcher m = TICKET_PARAM.matcher(location);
      if (m.find()) return m.group(1);
      Matcher g = TICKET_GENERIC.matcher(location);
      if (g.find()) return g.group(1);
    }
    if (body != null) {
      Matcher m = TICKET_PARAM.matcher(body);
      if (m.find()) return m.group(1);
      Matcher g = TICKET_GENERIC.matcher(body);
      if (g.find()) return g.group(1);
    }
    return null;
  }

  private Set<String> allowedHosts() {
    Set<String> hosts = new HashSet<>(GARMIN_HOSTS);
    if (apiBase.getHost() != null) hosts.add(apiBase.getHost().toLowerCase(Locale.ROOT));
    return hosts;
  }

  private static boolean allowedHost(URI uri, Set<String> allowedHosts) {
    String scheme = uri.getScheme();
    String host = uri.getHost();
    return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
        && host != null
        && allowedHosts.contains(host.toLowerCase(Locale.ROOT));
  }

  @Override
  public ConnectorResult<Session> connectWeb(Map<String, String> input) {
    if (!validFields(input)) return invalidSession("网页会话字段无效或过长");
    String cookie = required(input, "cookie");
    if (cookie == null) return invalidSession("请填写 Garmin 中国站登录后的 Cookie");
    if (cookie(cookie, "JWT_WEB") == null)
      return invalidSession("Garmin Cookie 中缺少 JWT_WEB，请登录后重新复制完整 Cookie");
    Map<String, String> values = new LinkedHashMap<>();
    values.put("cookie", cookie);
    for (String key : List.of("csrf", "ssoCookie", "userAgent")) {
      String value = optional(input, key);
      if (value != null) values.put(key, value);
    }
    String jwt = cookie(cookie, "JWT_WEB");
    Session session = new Session(jwt, optional(input, "externalUserId"), null, null, false, values);
    ConnectorResult<ActivityPage> probe = listActivities(session, 0, null);
    if (probe.status() == REQUIRES_LOGIN) {
      ConnectorResult<Session> refreshed = refreshSession(session);
      if (refreshed.status() != SUCCESS) return refreshed.castFailure();
      probe = listActivities(session, 0, null);
    }
    if (probe.status() != SUCCESS) return probe.castFailure();
    return ConnectorResult.success(session);
  }

  @Override
  public ConnectorResult<ActivityPage> listActivities(
      Session session, long sinceEpochMs, String cursor) {
    String cookie = cookie(session);
    if (cookie == null) return needsLogin();
    int start = Math.max(0, (pageNumber(cursor) - 1) * PAGE_SIZE);
    URI uri = apiBase.resolve("/gc-api/activitylist-service/activities/search/activities?start="
        + start + "&limit=" + PAGE_SIZE);
    ConnectorResult<WireResponse> result = gcApi(session, uri, null);
    if (result.status() != SUCCESS) return result.castFailure();
    try {
      JsonNode rows = JSON.readTree(result.value().bytes());
      if (!rows.isArray())
        return fail(RETRYABLE_FAILURE, "GARMIN_INVALID_RESPONSE", "Garmin 活动列表格式已变化");
      List<RemoteActivity> items = new ArrayList<>();
      for (JsonNode row : rows) {
        String id = row.path("activityId").asText("");
        if (id.isBlank()) continue;
        Long startAt = garminStart(row);
        if (startAt != null && startAt < sinceEpochMs) continue;
        items.add(new RemoteActivity(id, startAt,
            row.path("activityName").asText("").trim(),
            row.path("distance").asDouble(0) > 0 ? row.path("distance").asDouble() : null,
            null,
            Map.of("sport_type", row.path("activityType").path("typeKey").asText(""))));
      }
      boolean hasMore = rows.size() >= PAGE_SIZE && !items.isEmpty();
      return ConnectorResult.success(new ActivityPage(items,
          hasMore ? Integer.toString(pageNumber(cursor) + 1) : null, hasMore));
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "GARMIN_INVALID_RESPONSE", "Garmin 活动列表格式已变化");
    }
  }

  private static Long garminStart(JsonNode row) {
    String gmt = row.path("startTimeGMT").asText("").trim();
    if (!gmt.isBlank()) {
      try {
        return java.time.LocalDateTime.parse(gmt,
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .atZone(java.time.ZoneId.of("GMT")).toInstant().toEpochMilli();
      } catch (RuntimeException ignored) {
      }
    }
    String local = row.path("startTimeLocal").asText("").trim();
    if (local.isBlank()) return null;
    try {
      return java.time.LocalDateTime.parse(local,
          java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
          .atZone(CHINA).toInstant().toEpochMilli();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  @Override
  public ConnectorResult<FitDownload> downloadFit(Session session, RemoteActivity activity) {
    URI uri = URI.create(GC_API + "/download-service/files/activity/"
        + q(activity.remoteActivityId()) + "/fit");
    ConnectorResult<WireResponse> result = gcApi(session, uri, null);
    if (result.status() != SUCCESS) return result.castFailure();
    WireResponse wire = result.value();
    if (wire.status() == 404 || wire.status() == 410)
      return fail(PERMANENT_FAILURE, "NO_ORIGINAL_FILE", "Garmin 未保留该活动的原始 FIT");
    if (wire.status() < 200 || wire.status() >= 300) return statusFailure(wire.status(), "Garmin FIT");
    try {
      byte[] fit = extractFit(wire.bytes());
      if (fit == null) return fail(PERMANENT_FAILURE, "INVALID_FIT", "Garmin 返回的内容不是有效 FIT");
      return ConnectorResult.success(new FitDownload(fit, "garmin_" + activity.remoteActivityId() + ".fit"));
    } catch (Exception e) {
      return fail(RETRYABLE_FAILURE, "FIT_DOWNLOAD_FAILED", "Garmin FIT 读取失败");
    }
  }

  @Override
  public ConnectorResult<UploadResult> uploadFit(Session session, byte[] fitBytes, String fileName) {
    ConnectorResult<Void> valid = validateUpload(fitBytes);
    if (valid.status() != SUCCESS) return valid.castFailure();
    byte[] prepared = prepareGarminFit(fitBytes);
    String boundary = "FitSync" + hash("SHA-256", fitBytes).substring(0, 24);
    byte[] body;
    try {
      body = multipart(boundary,
          List.of(Part.file("file", "FitSync_" + hash("SHA-256", fitBytes).substring(0, 16) + ".fit", prepared)));
    } catch (Exception e) {
      return fail(PERMANENT_FAILURE, "UPLOAD_PREPARE_FAILED", "Garmin 上传内容准备失败");
    }
    ConnectorResult<WireResponse> result = gcApi(session,
        URI.create(GC_API + "/upload-service/upload"), body, "multipart/form-data; boundary=" + boundary);
    if (result.status() != SUCCESS) return uncertainUpload(fitBytes, "1");
    WireResponse wire = result.value();
    String bodyText;
    try {
      bodyText = new String(wire.bytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      bodyText = "";
    }
    ConnectorResult<UploadResult> parsed = parseGarminUpload(session, wire.status(), bodyText, fitBytes);
    if (parsed.status() == RETRYABLE_FAILURE || parsed.status() == REQUIRES_USER_ACTION)
      return uncertainUpload(fitBytes, "1");
    return parsed;
  }

  private ConnectorResult<UploadResult> parseGarminUpload(
      Session session, int status, String body, byte[] fitBytes) {
    JsonNode detail = null;
    try {
      detail = JSON.readTree(body).path("detailedImportResult");
    } catch (Exception ignored) {
    }
    if (detail != null && !detail.isMissingNode()) {
      JsonNode successes = detail.path("successes");
      if (successes.isArray() && !successes.isEmpty()) {
        String id = successes.get(0).path("internalId").asText("");
        if (!id.isBlank()) return ConnectorResult.success(new UploadResult(id));
      }
      JsonNode failures = detail.path("failures");
      if (failures.isArray() && !failures.isEmpty()) {
        JsonNode messages = failures.get(0).path("messages");
        boolean duplicate = false;
        for (JsonNode message : messages) {
          String code = message.path("code").asText("");
          String content = message.path("content").asText("");
          if ("202".equals(code) || "duplicate.activity".equals(code)
              || content.toLowerCase(Locale.ROOT).contains("duplicate")) duplicate = true;
        }
        String id = failures.get(0).path("internalId").asText("");
        if (duplicate)
          return ConnectorResult.duplicate(id.isBlank() ? UploadResult.DUPLICATE_WITHOUT_ID
              : new UploadResult(id));
        return fail(PERMANENT_FAILURE, "GARMIN_IMPORT_REJECTED", "Garmin 拒绝导入该 FIT 文件");
      }
    }
    if (status == 409)
      return ConnectorResult.duplicate(UploadResult.DUPLICATE_WITHOUT_ID);
    if (status == 401 || status == 403) return needsLogin();
    if (status == 429 || status >= 500) return statusFailure(status, "Garmin 上传");
    if (status < 200 || status >= 300)
      return fail(PERMANENT_FAILURE, "HTTP_" + status, "Garmin 上传失败");
    return ConnectorResult.processing(pendingToken(fitBytes, "1"));
  }

  /** 仅修复上传副本的 FIT CRC；解析器失败时交由服务端拒绝，不伪造结构。 */
  byte[] prepareGarminFit(byte[] source) {
    if (source.length < 14 || source[8] != '.' || source[9] != 'F' || source[10] != 'I'
        || source[11] != 'T') return source;
    int header = source[0] & 0xff;
    if (header != 12 && header != 14) return source;
    long data = (source[4] & 0xffL) | ((source[5] & 0xffL) << 8)
        | ((source[6] & 0xffL) << 16) | ((source[7] & 0xffL) << 24);
    long total = header + data + 2;
    if (total != source.length) return source;
    byte[] copy = source.clone();
    if (header == 14) {
      int stored = (copy[12] & 0xff) | ((copy[13] & 0xff) << 8);
      int actual = fitCrc(copy, 0, 12);
      if (stored != 0 && stored != actual) {
        copy[12] = (byte) actual;
        copy[13] = (byte) (actual >>> 8);
      }
    }
    int offset = (int) (header + data);
    int stored = (copy[offset] & 0xff) | ((copy[offset + 1] & 0xff) << 8);
    int actual = fitCrc(copy, 0, offset);
    if (stored != actual) {
      copy[offset] = (byte) actual;
      copy[offset + 1] = (byte) (actual >>> 8);
    }
    return copy;
  }

  int fitCrc(byte[] bytes, int offset, int length) {
    int crc = 0;
    for (int i = offset; i < offset + length; i++) {
      crc ^= bytes[i] & 0xff;
      for (int bit = 0; bit < 8; bit++)
        crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
    }
    return crc & 0xffff;
  }

  /** 单次 gc-api 请求；403/登录跳转先静默续期并只重放当前请求。 */
  ConnectorResult<WireResponse> gcApi(Session session, URI uri, byte[] body) {
    return gcApi(session, uri, body, null);
  }

  ConnectorResult<WireResponse> gcApi(
      Session session, URI uri, byte[] body, String contentType) {
    ConnectorResult<WireResponse> first = gcApiOnce(session, uri, body, contentType);
    if (first.status() != REQUIRES_LOGIN) return first;
    if (body != null)
      // 上传正文一旦发出，续期后重放仍可能造成重复活动；只允许无正文的读取请求恢复一次。
      return first;
    ConnectorResult<Session> renewed = refreshSession(session);
    if (renewed.status() == SUCCESS) {
      return gcApiOnce(session, uri, body, contentType);
    }
    if (reloginIfPossible(session)) {
      return gcApiOnce(session, uri, body, contentType);
    }
    return renewed.castFailure();
  }

  ConnectorResult<WireResponse> gcApiOnce(
      Session session, URI uri, byte[] body, String contentType) {
    String cookie = cookie(session);
    if (cookie == null) return needsLogin();
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(90))
        .header("Cookie", cookie)
        .header("NK", "NT")
        .header("DI-Backend", "connectapi.garmin.cn")
        .header("X-Requested-With", "XMLHttpRequest")
        .header("Accept", "application/json, text/plain, */*")
        .header("Origin", BASE)
        .header("Referer", BASE + "/app/")
        .header("Sec-Fetch-Dest", "empty")
        .header("Sec-Fetch-Mode", "cors")
        .header("Sec-Fetch-Site", "same-origin")
        .header("User-Agent", browserAgent(session));
    String csrf = session.attribute("csrf");
    if (csrf != null) builder.header("Connect-Csrf-Token", csrf);
    if (contentType != null) builder.header("Content-Type", contentType);
    if (contentType != null || body != null) {
      builder.method("POST", body == null ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofByteArray(body));
    } else {
      builder.GET();
    }
    ConnectorResult<WireResponse> result = sendWeb(builder.build(), "Garmin 请求");
    if (result.status() != SUCCESS) return result;
    WireResponse wire = result.value();
    captureCookies(session, wire);
    if (wire.status() == 401) return needsLogin();
    if (wire.status() >= 300 && wire.status() < 400) {
      String location = wire.header("Location");
      if (location != null && (location.contains("sso.garmin")
          || location.toLowerCase().contains("/signin"))) return needsLogin();
    }
    if (wire.status() == 403) return needsLogin();
    return result;
  }

  /** 用 SSO Cookie 沿 CAS 链换发 JWT_WEB 与 CSRF；任何失败都回到重新登录。 */
  private ConnectorResult<Session> refreshSession(Session session) {
    String sso = session == null ? null : session.attribute("ssoCookie");
    if (blank(sso)) return needsLogin();
    String service = apiBase.resolve("/app/").toString();
    String current = ("connect.garmin.cn".equalsIgnoreCase(apiBase.getHost())
        ? "https://sso.garmin.cn"
        : apiBase.toString().replaceAll("/+$", ""))
        + "/sso/signin?clientId=GarminConnect&service="
        + q(service)
        + "&embed=false";
    String connectCookie = blank(session.attribute("cookie")) ? "" : session.attribute("cookie");
    String ssoCookie = sso;
    String jwt = null, csrf = null;
    Set<String> refreshHosts = allowedHosts();
    for (int hop = 0; hop < MAX_SSO_HOPS; hop++) {
      URI uri;
      try {
        uri = URI.create(current);
      } catch (RuntimeException e) {
        break;
      }
      if (!allowedHost(uri, refreshHosts)) break;
      boolean ssoHost = "sso.garmin.cn".equalsIgnoreCase(uri.getHost());
      String hopCookie = ssoHost ? ssoCookie : connectCookie;
      HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(60))
          .header("User-Agent", browserAgent(session))
          .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
          .header("Sec-Fetch-Dest", "document")
          .header("Sec-Fetch-Mode", "navigate")
          .header("Sec-Fetch-Site", "same-site")
          .header("Upgrade-Insecure-Requests", "1")
          .header("Referer", BASE + "/")
          .GET();
      if (!blank(hopCookie)) reqBuilder.header("Cookie", hopCookie);
      HttpRequest request = reqBuilder.build();
      ConnectorResult<WireResponse> response = sendWeb(request, "Garmin 会话续期");
      if (response.status() != SUCCESS) return response.castFailure();
      WireResponse wire = response.value();
      String merged = mergeCookies(ssoHost ? ssoCookie : connectCookie, wire.cookies());
      if (ssoHost) ssoCookie = merged == null ? "" : merged;
      else connectCookie = merged == null ? "" : merged;
      String newJwt = cookie(mergeCookies(null, wire.cookies()), "JWT_WEB");
      if (newJwt != null) jwt = newJwt;
      String body = new String(wire.bytes(), StandardCharsets.UTF_8);
      String found = extractCsrf(body);
      if (found != null) csrf = found;
      String location = wire.header("Location");
      if (wire.status() >= 300 && wire.status() < 400 && location != null) {
        try {
          current = uri.resolve(location).toString();
        } catch (RuntimeException e) {
          break;
        }
        continue;
      }
      if (ssoHost && wire.status() >= 200 && wire.status() < 300) {
        String ticket = extractTicket(null, body);
        if (!blank(ticket)) {
          current = apiBase.resolve("/app/?ticket=" + q(ticket)).toString();
          continue;
        }
      }
      break;
    }
    if (jwt == null) return needsLogin();
    if (csrf != null) session.setAttribute("csrf", csrf);
    session.setAttribute("ssoCookie", ssoCookie);
    session.setAttribute("cookie",
        mergeCookies(connectCookie, List.of("JWT_WEB=" + jwt)));
    session.setAccessToken(jwt);
    return ConnectorResult.success(session);
  }

  private static String extractCsrf(String html) {
    if (html == null || html.isBlank()) return null;
    for (Pattern p : List.of(
        Pattern.compile("(?is)<meta[^>]+name=[\"']csrf-token[\"'][^>]+content=[\"']([^\"']+)[\"']"),
        Pattern.compile("(?is)<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+name=[\"']csrf-token[\"']"),
        Pattern.compile("(?i)[\"'](?:csrfToken|csrf_token)[\"']\\s*:\\s*[\"']([^\"']+)[\"']"))) {
      Matcher m = p.matcher(html);
      if (m.find() && !m.group(1).isBlank()) return m.group(1).trim();
    }
    return null;
  }
}
