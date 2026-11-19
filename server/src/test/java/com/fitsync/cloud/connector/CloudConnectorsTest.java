package com.fitsync.cloud.connector;

import static com.fitsync.cloud.connector.ConnectorResult.Status.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

/** 全部响应由 HTTP stub 注入，不访问或伪造真实平台账号。 */
class CloudConnectorsTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-09T06:00:00Z"), ZoneOffset.UTC);
  private static final String IGP_FIT = "https://igp-zh.oss-cn-hangzhou.aliyuncs.com/saved.fit";
  private static final String COROS_FIT =
      "https://coros-oss.oss-cn-beijing.aliyuncs.com/saved.fit.zip";

  @Test
  void igpsportReloginRetriesOnceAndReturnsPersistableNewToken() throws Exception {
    StubClient http = new StubClient();
    http.reply(401, "{}");
    http.reply(200, "{\"code\":0,\"data\":{\"access_token\":\"fresh-token\",\"memberId\":\"u1\"}}");
    http.reply(200, "{\"code\":0,\"data\":{\"rows\":[]}}");
    Session session = session(true);
    ConnectorResult<ActivityPage> result = igp(http).listActivities(session, 0, null);
    assertEquals(SUCCESS, result.status());
    assertEquals("fresh-token", session.accessToken());
    assertEquals(3, http.requests.size());
    assertEquals(
        "Bearer old-token",
        http.requests.get(0).headers().firstValue("Authorization").orElseThrow());
    assertEquals(
        "Bearer fresh-token",
        http.requests.get(2).headers().firstValue("Authorization").orElseThrow());
    assertTrue(body(http.requests.get(1)).contains("igpsport-web"));
  }

  @Test
  void credentialsAreDiscardedUnlessUserExplicitlyAllowsStorage() {
    StubClient http = new StubClient();
    http.reply(401, "{}");
    Session session = session(false);
    assertNull(session.username());
    assertNull(session.password());
    assertFalse(session.toString().contains("old-token"));
    assertEquals(REQUIRES_LOGIN, igp(http).listActivities(session, 0, null).status());
    assertEquals(1, http.requests.size());
  }

  @Test
  void repeated401DoesNotLoopRelogin() {
    StubClient http = new StubClient();
    http.reply(401, "{}");
    http.reply(200, "{\"code\":0,\"data\":{\"access_token\":\"fresh\",\"memberId\":\"u1\"}}");
    http.reply(401, "{}");
    assertEquals(REQUIRES_LOGIN, igp(http).listActivities(session(true), 0, null).status());
    assertEquals(3, http.requests.size());
  }

  @Test
  void corosLoginHashesPasswordAndRefusesUnsupportedRegion() throws Exception {
    StubClient http = new StubClient();
    http.reply(
        200,
        "{\"result\":\"0000\",\"data\":{\"accessToken\":\"token\",\"userId\":123,\"regionId\":2}}");
    ConnectorResult<Session> login = coros(http).login("person@example.com", "password", false);
    assertEquals(SUCCESS, login.status());
    assertEquals("123", login.value().externalUserId());
    assertNull(login.value().password());
    String requestBody = body(http.requests.getFirst());
    assertTrue(requestBody.contains("5f4dcc3b5aa765d61d8327deb882cf99"));
    assertFalse(requestBody.contains("password"));
    http.reply(
        200,
        "{\"result\":\"0000\",\"data\":{\"accessToken\":\"token\",\"userId\":123,\"regionId\":1}}");
    assertEquals(REQUIRES_USER_ACTION, coros(http).login("name", "secret", true).status());
  }

  @Test
  void igpsportUploadIsPendingUntilDownloadedFitMatchesRealRideId() throws Exception {
    StubClient http = new StubClient();
    signedReply(http);
    http.reply(200, "");
    http.reply(200, "{\"code\":0,\"data\":{}}");
    IgpsportConnector connector = igp(http);
    Session session = session(false);
    ConnectorResult<UploadResult> upload = connector.uploadFit(session, fit(), "../活动.fit");
    assertEquals(PROCESSING, upload.status());
    assertNull(upload.value());
    assertNotNull(upload.confirmationToken());
    HttpRequest ossPut = http.requests.get(1);
    assertEquals("PUT", ossPut.method());
    assertTrue(ossPut.headers().firstValue("Content-Type").isEmpty());
    assertTrue(ossPut.headers().firstValue("Authorization").isEmpty());
    assertTrue(body(http.requests.get(2)).contains("\"ossName\":\"uploaded.fit\""));
    http.reply(
        200,
        "{\"code\":0,\"data\":{\"rows\":[{\"rideId\":987654,\"fitUrl\":\"" + IGP_FIT + "\"}]}}");
    http.reply(200, fit());
    ConnectorResult<UploadResult> checked =
        connector.checkUpload(session, upload.confirmationToken());
    assertEquals(SUCCESS, checked.status());
    assertEquals("987654", checked.value().remoteActivityId());
    assertEquals(
        1, http.requests.stream().filter(r -> r.uri().getPath().endsWith("uploadByOss")).count());
  }

  @Test
  void corosSignsZipAndConfirmsUsingRealLabelId() throws Exception {
    StubClient http = new StubClient();
    String creds =
        "{\"AccessKeyId\":\"ak\",\"AccessKeySecret\":\"sk\",\"SecurityToken\":\"sts-token\",\"Bucket\":\"coros-oss\",\"Region\":\"oss-cn-beijing\"}";
    String encoded = Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    http.reply(
        200, "{\"code\":200,\"data\":{\"credentials\":\"9y78gpoERW4lBNYL" + encoded + "\"}}");
    http.reply(200, "");
    http.reply(200, "{\"result\":\"0000\",\"data\":{}}");
    CorosConnector connector = coros(http);
    Session session = session(false);
    ConnectorResult<UploadResult> upload = connector.uploadFit(session, fit(), "activity.fit");
    assertEquals(PROCESSING, upload.status());
    assertEquals(
        "application/octet-stream",
        http.requests.get(1).headers().firstValue("Content-Type").orElseThrow());
    assertTrue(
        http.requests
            .get(1)
            .headers()
            .firstValue("Authorization")
            .orElseThrow()
            .startsWith("OSS ak:"));
    assertEquals(
        "sts-token",
        http.requests.get(1).headers().firstValue("x-oss-security-token").orElseThrow());
    assertFalse(http.requests.get(1).headers().firstValue("accesstoken").isPresent());
    assertTrue(body(http.requests.get(2)).contains("\"serviceName\":\"oss\""));
    assertTrue(body(http.requests.get(2)).contains("fit_zip/u1/"));
    http.reply(
        200,
        "{\"result\":\"0000\",\"data\":{\"totalPage\":1,\"dataList\":[{\"labelId\":\"label-real\",\"sportType\":65535}]}}");
    http.reply(200, "{\"result\":\"0000\",\"data\":{\"fileUrl\":\"" + COROS_FIT + "\"}}");
    http.reply(200, ConnectorHttpSupport.zip(fit(), "activity.fit"));
    ConnectorResult<UploadResult> checked =
        connector.checkUpload(session, upload.confirmationToken());
    assertEquals(SUCCESS, checked.status());
    assertEquals("label-real", checked.value().remoteActivityId());
    assertTrue(http.requests.get(4).uri().getRawQuery().contains("sportType=100"));
    assertEquals(
        1,
        http.requests.stream()
            .filter(r -> r.uri().getPath().equals("/activity/fit/import"))
            .count());
  }

  @Test
  void timeoutAfterCreationReturnsConfirmationAndNeverResubmits() {
    StubClient http = new StubClient();
    signedReply(http);
    http.reply(200, "");
    http.fail(new IOException("read timeout"));
    IgpsportConnector connector = igp(http);
    Session session = session(false);
    ConnectorResult<UploadResult> upload = connector.uploadFit(session, fit(), "activity.fit");
    assertEquals(PROCESSING, upload.status());
    assertNotNull(upload.confirmationToken());
    http.reply(200, "{\"code\":0,\"data\":{\"rows\":[]}}");
    ConnectorResult<UploadResult> pending =
        connector.checkUpload(session, upload.confirmationToken());
    assertEquals(PROCESSING, pending.status());
    assertNotEquals(upload.confirmationToken(), pending.confirmationToken());
    assertEquals(
        1, http.requests.stream().filter(r -> r.uri().getPath().endsWith("uploadByOss")).count());
  }

  @Test
  void unknownCreationResponseRemainsPendingInsteadOfBlindlyRetrying() {
    StubClient http = new StubClient();
    signedReply(http);
    http.reply(200, "");
    http.reply(200, "{\"data\":{\"accepted\":true}}");
    ConnectorResult<UploadResult> result = igp(http).uploadFit(session(false), fit(), "a.fit");
    assertEquals(PROCESSING, result.status());
    assertNotNull(result.confirmationToken());
    assertEquals(3, http.requests.size());
  }

  @Test
  void exactHttpsWhitelistRejectsSuffixConfusionCredentialsPortsAndHttp() {
    StubClient http = new StubClient();
    IgpsportConnector connector = igp(http);
    for (String url :
        List.of(
            "http://igp-zh.oss-cn-hangzhou.aliyuncs.com/a.fit",
            "https://igp-zh.oss-cn-hangzhou.aliyuncs.com.evil.example/a.fit",
            "https://evilaliyuncs.com/a.fit",
            "https://127.0.0.1/a.fit",
            "https://igp-zh.oss-cn-hangzhou.aliyuncs.com:8443/a.fit",
            "https://user:secret@igp-zh.oss-cn-hangzhou.aliyuncs.com/a.fit")) {
      assertEquals(
          PERMANENT_FAILURE, connector.downloadFit(session(false), activity(url)).status(), url);
    }
    assertTrue(http.requests.isEmpty());
  }

  @Test
  void object403DoesNotTriggerAccountReloginAndRedirectsAreNeverFollowed() {
    StubClient http = new StubClient();
    http.reply(403, "<Error>ExpiredToken</Error>");
    assertEquals(
        RETRYABLE_FAILURE, igp(http).downloadFit(session(true), activity(IGP_FIT)).status());
    http.reply(302, "");
    assertEquals(
        RETRYABLE_FAILURE, igp(http).downloadFit(session(true), activity(IGP_FIT)).status());
    assertEquals(2, http.requests.size());
    assertTrue(
        http.requests.stream().allMatch(r -> r.headers().firstValue("Authorization").isEmpty()));
  }

  @Test
  void htmlAndZipWithoutFitAreRejected() throws Exception {
    StubClient http = new StubClient();
    http.reply(200, "<html>login</html>");
    assertEquals("INVALID_FIT", igp(http).downloadFit(session(false), activity(IGP_FIT)).code());
    http.reply(
        200, ConnectorHttpSupport.zip("not fit".getBytes(StandardCharsets.UTF_8), "bad.fit"));
    assertEquals("INVALID_FIT", igp(http).downloadFit(session(false), activity(IGP_FIT)).code());
    assertThrows(
        IOException.class,
        () -> ConnectorHttpSupport.readBounded(new ByteArrayInputStream(new byte[17]), 16));
  }

  @Test
  void pendingExpiresWithoutPostingAnotherUpload() throws Exception {
    StubClient http = new StubClient();
    signedReply(http);
    http.reply(200, "");
    http.reply(200, "{\"code\":0}");
    Session session = session(false);
    String token = igp(http).uploadFit(session, fit(), "a.fit").confirmationToken();
    IgpsportConnector tomorrow =
        new IgpsportConnector(
            http,
            URI.create("https://prod.zh.igpsport.com"),
            Clock.fixed(CLOCK.instant().plus(Duration.ofHours(25)), ZoneOffset.UTC));
    assertEquals(REQUIRES_USER_ACTION, tomorrow.checkUpload(session, token).status());
    assertEquals(3, http.requests.size());
  }

  @Test
  void webConnectorsRejectIncompleteSessionsWithoutNetwork() {
    StubClient http = new StubClient();
    assertFalse(onelap(http).connectWeb(Map.of()).status() == SUCCESS);
    assertFalse(xingzhe(http).connectWeb(Map.of()).status() == SUCCESS);
    assertFalse(garmin(http).connectWeb(Map.of("cookie", "SESSION=no-jwt")).status() == SUCCESS);
    assertFalse(strava(http).connectWeb(Map.of("cookie", "other=value")).status() == SUCCESS);
    assertTrue(http.requests.isEmpty());
  }

  @Test
  void webSessionFieldsRejectHeaderInjectionAndOversize() {
    StubClient http = new StubClient();
    assertFalse(
        onelap(http)
                .connectWeb(Map.of("token", "bad\r\nInjected: header"))
                .status()
            == SUCCESS);
    assertFalse(
        strava(http)
                .connectWeb(Map.of("cookie", "x".repeat(70_000)))
                .status()
            == SUCCESS);
    assertTrue(http.requests.isEmpty());
  }

  @Test
  void stravaRejectsForeignObjectHostRedirectAndDoesNotLeakCookie() throws Exception {
    StubClient http = new StubClient();
    Session session =
        new Session(null, null, null, null, false,
            Map.of("cookie", "_strava4_session=secret-session"));
    ConnectorResult<FitDownload> foreign =
        strava(http).downloadFit(
            session,
            new RemoteActivity(
                "1", null, null, null, "https://evil.example/export_original", Map.of()));
    assertEquals(PERMANENT_FAILURE, foreign.status());
    assertEquals("UNTRUSTED_FIT_HOST", foreign.code());
    assertTrue(http.requests.isEmpty());
  }

  @Test
  void onelapRefreshRetriesOnlyOriginalRequestAndRotatesTokens() throws Exception {
    StubClient http = new StubClient();
    http.reply(401, "{}");
    http.reply(200, "{\"data\":{\"token\":\"fresh\",\"refresh_token\":\"refresh-2\"}}");
    http.reply(200, "{\"data\":{\"list\":[]}}");
    Session session =
        new Session("old", "u1", null, null, false,
            Map.of("refreshToken", "refresh-1", "cookie", "SESSION=abc"));
    ConnectorResult<ActivityPage> page = onelap(http).listActivities(session, 0, null);
    assertEquals(SUCCESS, page.status());
    assertEquals("fresh", session.accessToken());
    assertEquals("refresh-2", session.attribute("refreshToken"));
    assertEquals(3, http.requests.size());
  }

  @Test
  void xingzheUploadNeverRepostsAfterUncertainAcceptedResponse() throws Exception {
    StubClient http = new StubClient();
    http.reply(200, "{\"code\":0,\"data\":{}}");
    Session session =
        new Session(null, null, null, null, false, Map.of("cookie", "session=xyz"));
    ConnectorResult<UploadResult> upload = xingzhe(http).uploadFit(session, fit(), "ride.fit");
    assertEquals(PROCESSING, upload.status());
    assertNotNull(upload.confirmationToken());
    http.reply(200, "[]");
    ConnectorResult<UploadResult> pending =
        xingzhe(http).checkUpload(session, upload.confirmationToken());
    assertEquals(PROCESSING, pending.status());
    assertEquals(
        1,
        http.requests.stream()
            .filter(r -> r.uri().getPath().equals("/api/v1/fit/upload/"))
            .count());
  }

  @Test
  void stravaParsesCSRFAndConfirmsUploadWithRealActivityId() throws Exception {
    StubClient http = new StubClient();
    http.reply(
        200,
        "<html><meta name=\"csrf-token\" content=\"csrf-token-value\"></html>");
    http.reply(200, "{\"id\":987,\"workflow\":\"ready\"}");
    http.reply(
        200,
        "{\"id\":987,\"workflow\":\"success\",\"activity\":{\"id\":123456789}}");
    Session session =
        new Session(null, null, null, null, false, Map.of("cookie", "_strava4_session=x"));
    StravaConnector connector = strava(http);
    ConnectorResult<UploadResult> upload = connector.uploadFit(session, fit(), "ride.fit");
    assertEquals(PROCESSING, upload.status());
    assertEquals("987", upload.confirmationToken());
    assertEquals("csrf-token-value", session.attribute("csrf"));
    ConnectorResult<UploadResult> confirmed =
        connector.checkUpload(session, upload.confirmationToken());
    assertEquals(SUCCESS, confirmed.status());
    assertEquals("123456789", confirmed.value().remoteActivityId());
    assertFalse(body(http.requests.get(1)).contains("_strava4_session"));
    assertEquals(3, http.requests.size());
  }

  @Test
  void garminImportResultDistinguishesSuccessDuplicateAndRejectedFit() throws Exception {
    StubClient http = new StubClient();
    String success =
        "{\"detailedImportResult\":{\"successes\":[{\"internalId\":\"555\"}],\"failures\":[]}}";
    http.reply(200, success);
    Session session =
        new Session("jwt", "u1", null, null, false,
            Map.of("cookie", "JWT_WEB=jwt; SESSION=abc", "csrf", "csrf"));
    ConnectorResult<UploadResult> uploaded = garmin(http).uploadFit(session, fit(), "ride.fit");
    assertEquals(SUCCESS, uploaded.status());
    assertEquals("555", uploaded.value().remoteActivityId());

    http.reply(
        200,
        "{\"detailedImportResult\":{\"successes\":[],\"failures\":[{\"messages\":[{\"code\":202,\"content\":\"Duplicate Activity.\"}]}]}}");
    assertEquals(DUPLICATE, garmin(http).uploadFit(session, fit(), "ride.fit").status());

    http.reply(
        200,
        "{\"detailedImportResult\":{\"successes\":[],\"failures\":[{\"messages\":[{\"code\":500,\"content\":\"bad fit\"}]}]}}");
    assertEquals(
        PERMANENT_FAILURE, garmin(http).uploadFit(session, fit(), "ride.fit").status());
    assertEquals(3, http.requests.size());
  }

  @Test
  void garminActivityRequestsStayOnChinaGcApiHost() {
    StubClient http = new StubClient();
    http.reply(200, "[]");
    Session session =
        new Session("jwt", null, null, null, false, Map.of("cookie", "JWT_WEB=jwt"));
    ConnectorResult<ActivityPage> page = garmin(http).listActivities(session, 0, null);
    assertEquals(SUCCESS, page.status());
    assertEquals(
        "connect.garmin.cn", http.requests.getFirst().uri().getHost());
    assertTrue(http.requests.getFirst().uri().getPath().startsWith("/gc-api/"));
    assertFalse(http.requests.getFirst().headers().firstValue("Cookie").orElse("").contains("null"));
  }

  @Test
  void onelapLoginHashesPasswordAndStoresRefreshTokenAndReloginsOnExpiredRefresh()
      throws Exception {
    StubClient http = new StubClient();
    http.reply(
        200,
        "{\"code\":200,\"data\":[{\"token\":\"onelap-jwt\",\"refresh_token\":\"onelap-rt\",\"uid\":\"u-onelap\"}]}",
        Map.of("Set-Cookie", List.of("JSESSIONID=ol-cookie; Path=/; HttpOnly")));
    ConnectorResult<Session> logged =
        onelap(http).login("13800138000", "secret-pass", true);
    assertEquals(SUCCESS, logged.status());
    Session session = logged.value();
    assertEquals("onelap-jwt", session.accessToken());
    assertEquals("u-onelap", session.externalUserId());
    assertEquals("onelap-rt", session.attribute("refreshToken"));
    assertEquals("JSESSIONID=ol-cookie", session.attribute("cookie"));
    String loginBody = body(http.requests.getFirst());
    assertTrue(loginBody.contains("591fac3e56ffbdc6f310c1b646050c09"));
    assertFalse(loginBody.contains("secret-pass"));
    assertTrue(http.requests.getFirst().headers().firstValue("sign").isPresent());

    http.reply(401, "{}");
    http.reply(401, "{}");
    http.reply(
        200,
        "{\"code\":200,\"data\":{\"token\":\"onelap-jwt-2\",\"refresh_token\":\"onelap-rt-2\",\"uid\":\"u-onelap\"}}");
    http.reply(200, "{\"code\":200,\"data\":{\"list\":[]}}");
    ConnectorResult<ActivityPage> page = onelap(http).listActivities(session, 0, null);
    assertEquals(SUCCESS, page.status());
    assertEquals("onelap-jwt-2", session.accessToken());
    assertEquals("onelap-rt-2", session.attribute("refreshToken"));
    assertEquals(5, http.requests.size());
  }

  @Test
  void xingzheLoginEncryptsPasswordWithRsaCapturesCookieAndRelogins() throws Exception {
    StubClient http = new StubClient();
    http.reply(
        200,
        "{\"code\":0,\"data\":{\"userid\":\"xz-100\",\"username\":\"rider\"}}",
        Map.of("Set-Cookie", List.of("sessionid=xz-sess-1; Path=/; HttpOnly")));
    ConnectorResult<Session> logged =
        xingzhe(http).login("13900139000", "xz-secret", true);
    assertEquals(SUCCESS, logged.status());
    Session session = logged.value();
    assertEquals("xz-100", session.externalUserId());
    assertEquals("sessionid=xz-sess-1", session.attribute("cookie"));
    String loginBody = body(http.requests.getFirst());
    assertFalse(loginBody.contains("xz-secret"));
    assertTrue(loginBody.contains("\"password\":"));

    http.reply(401, "{}");
    http.reply(
        200,
        "{\"code\":0,\"data\":{\"userid\":\"xz-100\",\"username\":\"rider\"}}",
        Map.of("Set-Cookie", List.of("sessionid=xz-sess-2; Path=/; HttpOnly")));
    http.reply(200, "[]");
    ConnectorResult<ActivityPage> page = xingzhe(http).listActivities(session, 0, null);
    assertEquals(SUCCESS, page.status());
    assertEquals("sessionid=xz-sess-2", session.attribute("cookie"));
    assertEquals(4, http.requests.size());
  }

  @Test
  void garminLoginExchangesSsoTicketForJwtWebAndSsoCookie() throws Exception {
    StubClient http = new StubClient();
    http.reply(
        200,
        "<form><input type=\"hidden\" name=\"_csrf\" value=\"form-csrf-1\"></form>",
        Map.of("Set-Cookie", List.of("GARMIN-SSO=sso1; Path=/")));
    http.reply(
        302,
        "",
        Map.of(
            "Location", List.of("https://connect.garmin.cn/app/?ticket=ST-12345"),
            "Set-Cookie", List.of("CASTGC=TGT-999; Path=/")));
    http.reply(
        200,
        "<html><meta name=\"csrf-token\" content=\"connect-csrf-1\"></html>",
        Map.of("Set-Cookie", List.of("JWT_WEB=jwt-web-1; Path=/", "SESSION=s1; Path=/")));
    ConnectorResult<Session> logged =
        garmin(http).login("garmin@example.com", "garmin-pass", true);
    assertEquals(SUCCESS, logged.status());
    Session session = logged.value();
    assertEquals("jwt-web-1", session.accessToken());
    assertEquals("connect-csrf-1", session.attribute("csrf"));
    assertTrue(session.attribute("ssoCookie").contains("CASTGC=TGT-999"));
    assertTrue(session.attribute("cookie").contains("JWT_WEB=jwt-web-1"));
    String postBody = body(http.requests.get(1));
    assertTrue(postBody.contains("_csrf=form-csrf-1"));
    assertTrue(postBody.contains("embed=false"));
    assertFalse(postBody.contains("locale=zh-CN"));
    assertFalse(postBody.contains("clientId="));
    assertFalse(postBody.contains("service="));
    assertEquals(3, http.requests.size());
  }

  @Test
  void garminLoginKeepsJwtWebWhenSameResponseClearsLegacyDomainCookie() throws Exception {
    for (List<String> setCookies :
        List.of(
            List.of(
                "JWT_WEB=jwt-web-1; Path=/",
                "JWT_WEB=; Max-Age=0; Domain=.connect.garmin.cn; Path=/",
                "SESSION=s1; Path=/"),
            List.of(
                "JWT_WEB=; Max-Age=0; Domain=.connect.garmin.cn; Path=/",
                "JWT_WEB=jwt-web-2; Path=/",
                "SESSION=s2; Path=/"))) {
      StubClient http = new StubClient();
      http.reply(
          200,
          "<form><input type=\"hidden\" name=\"_csrf\" value=\"form-csrf-1\"></form>",
          Map.of("Set-Cookie", List.of("GARMIN-SSO=sso1; Path=/")));
      http.reply(
          302,
          "",
          Map.of(
              "Location", List.of("https://connect.garmin.cn/app/?ticket=ST-12345"),
              "Set-Cookie", List.of("CASTGC=TGT-999; Path=/")));
      http.reply(
          200,
          "<html><meta name=\"csrf-token\" content=\"connect-csrf-1\"></html>",
          Map.of("Set-Cookie", setCookies));
      ConnectorResult<Session> logged =
          garmin(http).login("garmin@example.com", "garmin-pass", true);
      assertEquals(SUCCESS, logged.status());
      String expectedJwt = setCookies.stream().filter(s -> s.contains("jwt-web-")).findFirst().get();
      String jwtValue = expectedJwt.split(";", 2)[0].split("=", 2)[1];
      assertEquals(jwtValue, logged.value().accessToken());
      assertTrue(logged.value().attribute("cookie").contains("JWT_WEB=" + jwtValue));
    }
    assertEquals(
        "SESSION=s1",
        WebSessionConnector.mergeCookies(
            "JWT_WEB=old; SESSION=s1",
            List.of("JWT_WEB=; Max-Age=0; Domain=.connect.garmin.cn; Path=/")));
  }

  @Test
  void garminLoginFollowsSsoRedirectAndDetectsCloudflareChallenge() throws Exception {
    StubClient http = new StubClient();
    http.reply(
        200,
        "<form><input type=\"hidden\" name=\"_csrf\" value=\"form-csrf-1\"></form>",
        Map.of("Set-Cookie", List.of("GARMIN-SSO=sso1; Path=/")));
    http.reply(
        302,
        "",
        Map.of(
            "Location", List.of("https://connect.garmin.cn/modern/?ticket=ST-999"),
            "Set-Cookie", List.of("CASTGC=TGT-999; Path=/")));
    http.reply(
        403,
        "<html>Just a moment...</html>",
        Map.of("cf-mitigated", List.of("challenge")));

    ConnectorResult<Session> result =
        garmin(http).login("garmin@example.com", "garmin-pass", true);
    assertEquals(REQUIRES_USER_ACTION, result.status());
    assertEquals("GARMIN_CAPTCHA", result.code());
    assertEquals(3, http.requests.size());
    assertEquals(
        "https://connect.garmin.cn/modern/?ticket=ST-999",
        http.requests.get(2).uri().toString());
  }

  @Test
  void garminLoginInvalidCredentialsWithRecaptchaScriptIsAuthFailed() throws Exception {
    StubClient http = new StubClient();
    http.reply(
        200,
        "<form><input type=\"hidden\" name=\"_csrf\" value=\"form-csrf-1\"></form>");
    http.reply(
        401,
        "<html><script src=\"/sso/js/reCaptchaUtil.js\"></script>"
            + "<script>var isRecaptchaEnabled = null;</script>"
            + "<div class=\"error\">Invalid sign in.</div></html>");
    ConnectorResult<Session> result =
        garmin(http).login("garmin@example.com", "wrong-pass", true);
    assertEquals(PERMANENT_FAILURE, result.status());
    assertEquals("GARMIN_AUTH_FAILED", result.code());
  }

  @Test
  void garminLoginSharesParentDomainSsoCookiesWithConnectHost() throws Exception {
    StubClient http = new StubClient();
    http.reply(
        200,
        "<form><input type=\"hidden\" name=\"_csrf\" value=\"form-csrf-1\"></form>",
        Map.of("Set-Cookie", List.of("SESSION=sso-sess-1; Domain=sso.garmin.cn; Path=/sso/")));
    http.reply(
        302,
        "",
        Map.of(
            "Location", List.of("https://connect.garmin.cn/app/?ticket=ST-12345"),
            "Set-Cookie",
                List.of(
                    "CASTGC=TGT-999; Domain=sso.garmin.cn; Path=/sso/",
                    "GARMIN-SSO=1; Domain=.garmin.cn; Path=/",
                    "GARMIN-SSO-GUID=guid-1; Domain=.garmin.cn; Path=/",
                    "GARMIN-SSO-GUID=; Max-Age=0; Domain=sso.garmin.cn; Path=/")));
    http.reply(
        302,
        "",
        Map.of(
            "Location", List.of("https://connect.garmin.cn/app/"),
            "Set-Cookie", List.of("session=sess-1; Path=/")));
    http.reply(
        200,
        "<html><meta name=\"csrf-token\" content=\"csrf-2\"></html>",
        Map.of("Set-Cookie", List.of("JWT_WEB=jwt-2; Path=/")));

    ConnectorResult<Session> logged =
        garmin(http).login("garmin@example.com", "garmin-pass", true);
    assertEquals(SUCCESS, logged.status());
    Session session = logged.value();
    assertEquals("jwt-2", session.accessToken());
    assertEquals("csrf-2", session.attribute("csrf"));
    assertEquals(4, http.requests.size());

    String hop0Cookie = http.requests.get(2).headers().firstValue("Cookie").orElse("");
    assertTrue(hop0Cookie.contains("GARMIN-SSO=1"));
    assertTrue(hop0Cookie.contains("GARMIN-SSO-GUID=guid-1"));
    assertFalse(hop0Cookie.contains("CASTGC"));

    String hop1Cookie = http.requests.get(3).headers().firstValue("Cookie").orElse("");
    assertTrue(hop1Cookie.contains("GARMIN-SSO=1"));
    assertTrue(hop1Cookie.contains("GARMIN-SSO-GUID=guid-1"));
    assertTrue(hop1Cookie.contains("session=sess-1"));
    assertFalse(hop1Cookie.contains("CASTGC"));

    String savedCookie = session.attribute("cookie");
    assertTrue(savedCookie.contains("GARMIN-SSO-GUID=guid-1"));
    assertTrue(savedCookie.contains("session=sess-1"));
    assertTrue(savedCookie.contains("JWT_WEB=jwt-2"));
  }

  @Test
  void stravaLoginSubmitsAuthenticityTokenAndCapturesSessionCookie() throws Exception {
    StubClient http = new StubClient();
    http.reply(
        200,
        "<form><input type=\"hidden\" name=\"authenticity_token\" value=\"strava-csrf-1\"></form>",
        Map.of("Set-Cookie", List.of("_strava4_session=pre; Path=/")));
    http.reply(
        302,
        "",
        Map.of(
            "Location", List.of("https://www.strava.com/dashboard"),
            "Set-Cookie", List.of("_strava4_session=real-session-1; Path=/; HttpOnly")));
    ConnectorResult<Session> logged =
        strava(http).login("rider@example.com", "strava-pass", true);
    assertEquals(SUCCESS, logged.status());
    Session session = logged.value();
    assertEquals("_strava4_session=real-session-1", session.attribute("cookie"));
    String postBody = body(http.requests.get(1));
    assertTrue(postBody.contains("authenticity_token=strava-csrf-1"));
    assertTrue(postBody.contains("remember_me=on"));
    assertEquals(2, http.requests.size());
  }

  @Test
  void stravaOAuthExchangeRefreshListUploadAndSkipPull() throws Exception {
    assertTrue(StravaConnector.hasRequiredOAuthScopes("read,activity:read_all,activity:write"));
    assertFalse(StravaConnector.hasRequiredOAuthScopes("read"));
    String authorizeUrl =
        StravaConnector.authorizeUrl(
            "183007", "http://127.0.0.1:8080/api/v1/accounts/oauth/strava/callback", "state-1");
    assertTrue(authorizeUrl.contains("client_id=183007"));
    assertTrue(authorizeUrl.contains("response_type=code"));
    assertTrue(authorizeUrl.contains("state=state-1"));
    assertTrue(authorizeUrl.contains("activity%3Awrite"));

    StubClient http = new StubClient();
    long expiresFuture = CLOCK.millis() / 1000L + 3600L;
    http.reply(
        200,
        "{\"access_token\":\"acc-1\",\"refresh_token\":\"ref-1\",\"expires_at\":"
            + expiresFuture
            + ",\"athlete\":{\"id\":778899,\"firstname\":\"Lin\",\"lastname\":\"Rider\"}}");
    StravaConnector connector =
        new StravaConnector("183007", "app-secret", http, URI.create("https://www.strava.com"), CLOCK);

    ConnectorResult<Session> authRes =
        connector.exchangeOAuthCode(
            "183007", "app-secret", "auth-code-1", "read,activity:read_all,activity:write", false);
    assertEquals(SUCCESS, authRes.status());
    Session session = authRes.value();
    assertEquals("778899", session.externalUserId());
    assertEquals("Lin Rider", session.attribute("displayName"));
    assertEquals("OAUTH", session.attribute("authType"));
    assertEquals("acc-1", session.accessToken());
    assertEquals("ref-1", session.attribute("refreshToken"));
    assertNull(session.attribute("clientId"));
    assertEquals(1, http.requests.size());
    assertTrue(connector.skipPullAsDestination(session));

    ConnectorResult<FitDownload> dl =
        connector.downloadFit(session, activity("https://www.strava.com/activities/1/export_original"));
    assertEquals(PERMANENT_FAILURE, dl.status());
    assertEquals("NO_ORIGINAL_FILE", dl.code());
    assertEquals(1, http.requests.size());

    http.reply(401, "{}");
    http.reply(
        200,
        "{\"access_token\":\"acc-2\",\"refresh_token\":\"ref-2\",\"expires_at\":"
            + (expiresFuture + 3600L)
            + "}");
    http.reply(
        200,
        "[{\"id\":1001,\"name\":\"Morning Ride\",\"distance\":42195.0,"
            + "\"start_date\":\"2026-11-09T01:00:00Z\",\"sport_type\":\"Ride\","
            + "\"external_id\":\"fitsync-abc.fit\"}]");
    ConnectorResult<ActivityPage> page = connector.listActivities(session, 0L, null);
    assertEquals(SUCCESS, page.status());
    assertEquals(1, page.value().items().size());
    assertEquals("1001", page.value().items().getFirst().remoteActivityId());
    assertEquals("/api/v3/athlete/activities", http.requests.get(1).uri().getPath());
    assertTrue(http.requests.get(1).uri().getQuery().contains("per_page=100"));
    assertEquals(
        "Bearer acc-1",
        http.requests.get(1).headers().firstValue("Authorization").orElseThrow());
    assertEquals("/oauth/token", http.requests.get(2).uri().getPath());
    assertTrue(body(http.requests.get(2)).contains("refresh_token=ref-1"));
    assertEquals(
        "Bearer acc-2",
        http.requests.get(3).headers().firstValue("Authorization").orElseThrow());
    assertEquals("acc-2", session.accessToken());
    assertEquals("ref-2", session.attribute("refreshToken"));

    byte[] fitBytes = new byte[] {14, 0x10, 0, 0, 0, 0, 0, 0, '.', 'F', 'I', 'T', 0, 0, 0, 0};
    http.reply(201, "{\"id\":987654,\"status\":\"Your activity is still being processed.\"}");
    ConnectorResult<UploadResult> upRes = connector.uploadFit(session, fitBytes, "ride.fit");
    assertEquals(PROCESSING, upRes.status());
    assertEquals("987654", upRes.confirmationToken());
    assertEquals(15_000L, upRes.retryAfterMillis());
    assertEquals("/api/v3/uploads", http.requests.get(4).uri().getPath());
    assertEquals(
        "Bearer acc-2",
        http.requests.get(4).headers().firstValue("Authorization").orElseThrow());
    String uploadBody = body(http.requests.get(4));
    assertTrue(uploadBody.contains("external_id"));
    assertTrue(uploadBody.contains("fitsync-"));

    http.reply(429, "{}", Map.of("Retry-After", List.of("900")));
    ConnectorResult<UploadResult> limited = connector.checkUpload(session, "987654");
    assertEquals(RETRYABLE_FAILURE, limited.status());
    assertEquals("RATE_LIMITED", limited.code());
    assertEquals(900_000L, limited.retryAfterMillis());

    http.reply(200, "{\"id\":987654,\"status\":\"Your activity is still being processed.\"}");
    ConnectorResult<UploadResult> stillPending = connector.checkUpload(session, "987654");
    assertEquals(PROCESSING, stillPending.status());
    assertEquals(60_000L, stillPending.retryAfterMillis());

    http.reply(200, "{\"id\":987654,\"activity_id\":555666}");
    ConnectorResult<UploadResult> checked = connector.checkUpload(session, "987654");
    assertEquals(SUCCESS, checked.status());
    assertEquals("555666", checked.value().remoteActivityId());
    assertEquals(8, http.requests.size());
  }

  @Test
  void stravaGzipHtmlResponseDecompressesBounded() throws Exception {
    StubClient http = new StubClient();
    ByteArrayOutputStream gz = new ByteArrayOutputStream();
    try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
      out.write(
          "<html><head><meta name=\"csrf-token\" content=\"gz-csrf-1\"></head></html>"
              .getBytes(StandardCharsets.UTF_8));
    }
    http.reply(
        200,
        gz.toByteArray(),
        Map.of(
            "Content-Encoding", List.of("gzip"),
            "Set-Cookie", List.of("_strava4_session=gz-pre; Path=/")));
    http.reply(
        302,
        "",
        Map.of(
            "Location", List.of("/dashboard"),
            "Set-Cookie", List.of("_strava4_session=gz-auth; Path=/")));
    StravaConnector connector = strava(http);
    ConnectorResult<Session> res = connector.login("rider@example.com", "pw", false);
    assertEquals(SUCCESS, res.status());
    assertEquals(
        "gzip",
        http.requests.get(0).headers().firstValue("Accept-Encoding").orElseThrow());
    assertTrue(body(http.requests.get(1)).contains("authenticity_token=gz-csrf-1"));
    assertTrue(res.value().attribute("cookie").contains("_strava4_session=gz-auth"));
  }

  @Test
  void onelapCheckUploadInspectsSecondHalfOfPageInsteadOfSkipping() throws Exception {
    StubClient http = new StubClient();
    byte[] targetFit = fitWithByte(11);
    byte[] otherFit = fitWithByte(99);
    Session session = new Session("ol-jwt", "u1", null, null, false);
    OnelapConnector connector = onelap(http);

    http.reply(200, "{\"code\":200,\"data\":{\"accepted\":true}}");
    ConnectorResult<UploadResult> upload = connector.uploadFit(session, targetFit, "ride.fit");
    assertEquals(PROCESSING, upload.status());

    StringBuilder rowsJson = new StringBuilder("[");
    for (int i = 1; i <= 10; i++) {
      if (i > 1) rowsJson.append(',');
      rowsJson
          .append("{\"id\":\"rec-")
          .append(i)
          .append("\",\"name\":\"Ride ")
          .append(i)
          .append("\",\"fit_url\":\"https://files.onelap.cn/")
          .append(i)
          .append(".fit\"}");
    }
    rowsJson.append("]");
    String listBody = "{\"code\":200,\"data\":{\"list\":" + rowsJson + "}}";

    http.reply(200, listBody);
    for (int i = 1; i <= 5; i++) http.reply(200, otherFit);
    ConnectorResult<UploadResult> firstCheck =
        connector.checkUpload(session, upload.confirmationToken());
    assertEquals(PROCESSING, firstCheck.status());

    http.reply(200, listBody);
    http.reply(200, otherFit);
    http.reply(200, otherFit);
    http.reply(200, targetFit);
    ConnectorResult<UploadResult> secondCheck =
        connector.checkUpload(session, firstCheck.confirmationToken());
    assertEquals(SUCCESS, secondCheck.status());
    assertEquals("rec-8", secondCheck.value().remoteActivityId());
  }

  @Test
  void onelapListAndDownloadSupportNestedRidingRecordAndLegacyWebConfirmation() throws Exception {
    StubClient http = new StubClient();
    Session session = new Session("ol-jwt", "u1", null, null, false);
    OnelapConnector connector = onelap(http);

    http.reply(
        200,
        "{\"code\":200,\"data\":{\"list\":[{\"id\":42,\"name\":\"Mahdha 虚拟骑行\","
            + "\"start_riding_time\":\"2026-11-19 15:39:00\",\"distance_km\":12.5,"
            + "\"ridingRecord\":{\"fitUrl\":\"https://files.onelap.cn/activities/42.fit\"}}]}}");
    ConnectorResult<ActivityPage> page = connector.listActivities(session, 0, null);
    assertEquals(SUCCESS, page.status());
    assertEquals(1, page.value().items().size());
    RemoteActivity remote = page.value().items().getFirst();
    assertEquals("42", remote.remoteActivityId());
    assertEquals(12_500.0, remote.distanceMeters());
    assertEquals("https://files.onelap.cn/activities/42.fit", remote.downloadUrl());

    byte[] expectedFit = fitWithByte(42);
    http.reply(
        200,
        "{\"code\":200,\"data\":\"https://files.onelap.cn/signed/42.fit\"}",
        Map.of("Content-Type", List.of("application/json;charset=UTF-8")));
    http.reply(200, expectedFit);
    ConnectorResult<FitDownload> downloaded = connector.downloadFit(session, remote);
    assertEquals(SUCCESS, downloaded.status());
    assertArrayEquals(expectedFit, downloaded.value().bytes());

    String legacyJson =
        "{\"platform\":\"ONELAP\",\"sha256\":\""
            + ConnectorHttpSupport.hash("SHA-256", expectedFit)
            + "\",\"hint\":\"1\",\"submittedAt\":"
            + CLOCK.millis()
            + "}";
    String legacyToken =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(legacyJson.getBytes(StandardCharsets.UTF_8));
    http.reply(
        200,
        "{\"code\":200,\"data\":{\"list\":[{\"id\":42,\"ridingRecord\":{\"fitUrl\":\"42.fit\"}}]}}");
    http.reply(200, expectedFit);
    ConnectorResult<UploadResult> legacyChecked = connector.checkUpload(session, legacyToken);
    assertEquals(SUCCESS, legacyChecked.status());
    assertEquals("42", legacyChecked.value().remoteActivityId());
  }

  private static byte[] fitWithByte(int marker) {
    byte[] value = fit();
    value[12] = (byte) marker;
    return value;
  }

  private static OnelapConnector onelap(StubClient http) {
    return new OnelapConnector(http, URI.create("https://otm.onelap.cn"), CLOCK);
  }

  private static XingzheConnector xingzhe(StubClient http) {
    return new XingzheConnector(http, URI.create("https://www.imxingzhe.com"), CLOCK);
  }

  private static GarminConnector garmin(StubClient http) {
    return new GarminConnector(http, URI.create("https://connect.garmin.cn"), CLOCK);
  }

  private static StravaConnector strava(StubClient http) {
    return new StravaConnector(http, URI.create("https://www.strava.com"), CLOCK);
  }

  private static Session session(boolean store) {
    return new Session("old-token", "u1", "account", "secret", store);
  }

  private static RemoteActivity activity(String url) {
    return new RemoteActivity("1", null, "活动", null, url, Map.of());
  }

  private static IgpsportConnector igp(StubClient http) {
    return new IgpsportConnector(http, URI.create("https://prod.zh.igpsport.com"), CLOCK);
  }

  private static CorosConnector coros(StubClient http) {
    return new CorosConnector(
        http,
        URI.create("https://teamcnapi.coros.com"),
        URI.create("https://faq.coros.com/openapi/oss/sts"),
        CLOCK);
  }

  private static void signedReply(StubClient http) {
    http.reply(
        200,
        "{\"code\":0,\"data\":{\"ossId\":\"uploaded.fit\",\"signedUrl\":\""
            + IGP_FIT
            + "?Signature=secret\"}}");
  }

  private static byte[] fit() {
    byte[] value = new byte[14];
    value[0] = 12;
    value[8] = '.';
    value[9] = 'F';
    value[10] = 'I';
    value[11] = 'T';
    return value;
  }

  private static String body(HttpRequest request) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    CountDownLatch done = new CountDownLatch(1);
    request
        .bodyPublisher()
        .orElseThrow()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
              }

              @Override
              public void onError(Throwable throwable) {
                done.countDown();
              }

              @Override
              public void onComplete() {
                done.countDown();
              }
            });
    assertTrue(done.await(2, TimeUnit.SECONDS));
    return output.toString(StandardCharsets.UTF_8);
  }

  static final class StubClient extends HttpClient {
    final List<HttpRequest> requests = new ArrayList<>();
    final Queue<Object> replies = new ArrayDeque<>();

    void reply(int status, String value) {
      reply(status, value.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    void reply(int status, String value, Map<String, List<String>> headers) {
      reply(status, value.getBytes(StandardCharsets.UTF_8), headers);
    }

    void reply(int status, byte[] value) {
      reply(status, value, Map.of());
    }

    void reply(int status, byte[] value, Map<String, List<String>> headers) {
      replies.add(new Reply(status, value, headers));
    }

    void fail(IOException exception) {
      replies.add(exception);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws IOException {
      requests.add(request);
      Object next = replies.remove();
      if (next instanceof IOException exception) throw exception;
      Reply reply = (Reply) next;
      HttpResponse<InputStream> response =
          new HttpResponse<>() {
            public int statusCode() {
              return reply.status;
            }

            public HttpRequest request() {
              return request;
            }

            public Optional<HttpResponse<InputStream>> previousResponse() {
              return Optional.empty();
            }

            public HttpHeaders headers() {
              return HttpHeaders.of(reply.headers, (a, b) -> true);
            }

            public InputStream body() {
              return new ByteArrayInputStream(reply.bytes);
            }

            public Optional<SSLSession> sslSession() {
              return Optional.empty();
            }

            public URI uri() {
              return request.uri();
            }

            public Version version() {
              return Version.HTTP_1_1;
            }
          };
      return (HttpResponse<T>) response;
    }

    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    public SSLContext sslContext() {
      try {
        return SSLContext.getDefault();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    public Version version() {
      return Version.HTTP_1_1;
    }

    public Optional<Executor> executor() {
      return Optional.empty();
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException();
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> push) {
      throw new UnsupportedOperationException();
    }

    private record Reply(int status, byte[] bytes, Map<String, List<String>> headers) {}
  }
}
