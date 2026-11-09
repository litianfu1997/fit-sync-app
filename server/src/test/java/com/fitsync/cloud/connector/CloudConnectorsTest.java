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
      reply(status, value.getBytes(StandardCharsets.UTF_8));
    }

    void reply(int status, byte[] value) {
      replies.add(new Reply(status, value));
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
              return HttpHeaders.of(Map.of(), (a, b) -> true);
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

    private record Reply(int status, byte[] bytes) {}
  }
}
