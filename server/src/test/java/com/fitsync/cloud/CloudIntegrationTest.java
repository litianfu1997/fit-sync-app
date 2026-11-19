package com.fitsync.cloud;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fitsync.cloud.connector.*;
import com.fitsync.fitcore.FitParser;
import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:cloudtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "fitsync.storage-dir=./target/test-fits",
      "fitsync.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "fitsync.scheduler-enabled=false",
      "fitsync.auth-rate-limit=1000",
      "fitsync.strava.client-id=",
      "fitsync.strava.client-secret=",
      "fitsync.strava.redirect-uri="
    })
@ActiveProfiles("local")
@AutoConfigureMockMvc
class CloudIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired CloudStore store;
  @Autowired ActivityService activities;
  @Autowired JobService jobs;
  @Autowired CryptoService crypto;
  @Autowired PlatformTransactionManager transactions;
  @MockitoSpyBean AccountService accounts;
  private PlatformConnector connector;

  @BeforeEach
  void connector() {
    connector = mock(PlatformConnector.class);
    doReturn(connector).when(accounts).connector("COROS");
    when(connector.login(anyString(), anyString(), anyBoolean()))
        .thenAnswer(
            call ->
                ConnectorResult.success(
                    new Session(
                        "secret-access-token",
                        "external-id",
                        call.getArgument(0),
                        call.getArgument(1),
                        call.getArgument(2))));
  }

  record Login(String token, String user) {}

  private Login login() throws Exception {
    var response =
        mvc.perform(
                post("/api/v1/auth/dev-login")
                    .contentType("application/json")
                    .content("{\"displayName\":\"测试骑友\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var json = store.json.readTree(response.getContentAsString());
    return new Login(json.get("token").asText(), json.get("user").get("id").asText());
  }

  private String account(Login login) throws Exception {
    var response =
        mvc.perform(
                post("/api/v1/accounts")
                    .header("Authorization", "Bearer " + login.token())
                    .contentType("application/json")
                    .content(
                        "{\"platform\":\"COROS\",\"username\":\"rider@example.com\",\"password\":\"very-secret-password\",\"storeCredentials\":true}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    String body = response.getContentAsString();
    assertFalse(body.contains("very-secret-password"));
    assertFalse(body.contains("secret-access-token"));
    return store.json.readTree(body).get("id").asText();
  }

  private String imported(Login login) throws Exception {
    var response =
        mvc.perform(
                multipart("/api/v1/activities/import")
                    .file(
                        new MockMultipartFile(
                            "file", "ride.fit", "application/octet-stream", fit()))
                    .header("Authorization", "Bearer " + login.token()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return store.json.readTree(response.getContentAsString()).get("activityId").asText();
  }

  @Test
  void sessionsAreRandomHashedAndLogoutRevokes() throws Exception {
    Login first = login(), second = login();
    assertNotEquals(first.user(), second.user());
    assertNotEquals(first.token(), second.token());
    var hash =
        store.db.queryForObject(
            "SELECT token_hash FROM user_session WHERE user_id=?", String.class, first.user());
    assertEquals(CryptoService.hash(first.token()), hash);
    assertNotEquals(first.token(), hash);
    mvc.perform(get("/api/v1/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + first.token()))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + first.token()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void fitImportIsIdempotentWithinOneUserAndPrivateAcrossUsers() throws Exception {
    Login alice = login(), bob = login();
    String activity = imported(alice);
    mvc.perform(
            multipart("/api/v1/activities/import")
                .file(new MockMultipartFile("file", "again.fit", "application/octet-stream", fit()))
                .header("Authorization", "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activityId").value(activity))
        .andExpect(jsonPath("$.duplicate").value(true));
    mvc.perform(
            get("/api/v1/activities/" + activity).header("Authorization", "Bearer " + bob.token()))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/activities/" + activity + "/file")
                .header("Authorization", "Bearer " + bob.token()))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/activities/" + activity + "/file")
                .header("Authorization", "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(content().bytes(fit()));
    String own = imported(bob);
    assertNotEquals(activity, own);
    mvc.perform(get("/api/v1/activities").header("Authorization", "Bearer " + bob.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
  }

  @Test
  void rejectsCorruptFitWithoutCreatingActivity() throws Exception {
    Login login = login();
    byte[] corrupt = fit();
    corrupt[corrupt.length - 1] ^= 1;
    mvc.perform(
            multipart("/api/v1/activities/import")
                .file(
                    new MockMultipartFile("file", "ride.fit", "application/octet-stream", corrupt))
                .header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isBadRequest());
    assertEquals(
        0,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM activity WHERE user_id=?", Integer.class, login.user()));
  }

  @Test
  void credentialsAreEncryptedAndBoundToOwnerAndAccount() throws Exception {
    Login login = login();
    String account = account(login);
    var row = store.owned("platform_account", account, login.user());
    String encrypted = CloudStore.str(row, "encrypted_session");
    assertFalse(encrypted.contains("very-secret-password"));
    assertFalse(encrypted.contains("secret-access-token"));
    assertEquals("very-secret-password", accounts.session(row).password());
    assertThrows(
        IllegalStateException.class, () -> crypto.decrypt(encrypted, "different-user:" + account));
    mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("very-secret-password"))));
    Login other = login();
    mvc.perform(
            delete("/api/v1/accounts/" + account)
                .header("Authorization", "Bearer " + other.token()))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/rules")
                .header("Authorization", "Bearer " + other.token())
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of(
                            "sourcePlatform",
                            "LOCAL",
                            "destinationAccountId",
                            account,
                            "enabled",
                            true,
                            "includeHistory",
                            true))))
        .andExpect(status().isNotFound());
  }

  @Test
  void expiredUploadLeaseNeverPostsAgain() throws Exception {
    Login login = login();
    String account = account(login),
        activity = imported(login),
        job = jobs.delivery(login.user(), activity, account);
    store.db.update(
        "UPDATE sync_job SET"
            + " status='RUNNING',lease_until=0,lease_owner='old-process',confirmation_json='UPLOAD_STARTED'"
            + " WHERE id=?",
        job);
    jobs.runOne(job);
    assertEquals(
        "WAITING_USER", store.one("SELECT status FROM sync_job WHERE id=?", job).get("status"));
    verify(connector, never()).uploadFit(any(), any(), any());
    assertThrows(ApiException.class, () -> jobs.retry(login.user(), job));
    Login other = login();
    mvc.perform(
            post("/api/v1/jobs/" + job + "/retry")
                .header("Authorization", "Bearer " + other.token()))
        .andExpect(status().isNotFound());
  }

  @Test
  void processingUploadResumesConfirmationAndMapsRealId() throws Exception {
    Login login = login();
    String account = account(login),
        activity = imported(login),
        job = jobs.delivery(login.user(), activity, account);
    when(connector.uploadFit(any(), any(), any()))
        .thenReturn(ConnectorResult.processing("persisted-confirmation-token"));
    when(connector.checkUpload(any(), eq("persisted-confirmation-token")))
        .thenReturn(ConnectorResult.success(new UploadResult("real-remote-activity")));
    jobs.runOne(job);
    assertEquals(
        "CONFIRMING", store.one("SELECT status FROM sync_job WHERE id=?", job).get("status"));
    store.db.update("UPDATE sync_job SET next_run_at=0 WHERE id=?", job);
    jobs.runOne(job);
    verify(connector, times(1)).uploadFit(any(), any(), any());
    verify(connector, times(1)).checkUpload(any(), any());
    assertEquals(
        "SUCCEEDED", store.one("SELECT status FROM sync_job WHERE id=?", job).get("status"));
    assertEquals(
        "real-remote-activity",
        store
            .one(
                "SELECT remote_id FROM platform_copy WHERE user_id=? AND activity_id=? AND"
                    + " account_id=?",
                login.user(),
                activity,
                account)
            .get("remote_id"));
    assertNull(jobs.delivery(login.user(), activity, account));
  }

  @Test
  void storeCredentialsFalseDoesNotRetainPassword() throws Exception {
    Login login = login();
    String account = account(login);
    var row = store.owned("platform_account", account, login.user());
    accounts.saveSession(
        row,
        new Session(
            "short-lived-token", "external-id", "username", "password-must-disappear", false));
    var stored = accounts.session(store.owned("platform_account", account, login.user()));
    assertNull(stored.password());
    assertNull(stored.username());
    assertFalse(stored.storeCredentials());
  }

  @Test
  void adsDoNotOfferUnverifiedRewards() throws Exception {
    mvc.perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ads.rewardVerificationEnabled").value(false))
        .andExpect(jsonPath("$.ads.rewardedAdUnitId").value(""));
  }

  @Test
  void webLoginRequiresConfirmationAndOriginalBrowserSecretAndCanOnlyBeConsumedOnce()
      throws Exception {
    Login owner = login(), other = login();
    var response =
        mvc.perform(post("/api/v1/auth/web-challenges"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var challenge = store.json.readTree(response.getContentAsString());
    String id = challenge.get("id").asText(),
        code = challenge.get("code").asText(),
        poll = challenge.get("pollToken").asText();
    assertEquals(10, code.length());
    var stored = store.one("SELECT * FROM web_login_challenge WHERE id=?", id);
    assertEquals(CryptoService.hash(code), stored.get("code_hash"));
    assertEquals(CryptoService.hash(poll), stored.get("poll_hash"));
    mvc.perform(get("/api/v1/auth/web-challenges/" + id).header("X-Login-Poll-Token", poll))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"));
    mvc.perform(
            get("/api/v1/auth/web-challenges/" + id).header("X-Login-Poll-Token", "wrong-browser"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/auth/web-challenges/confirm")
                .header("Authorization", "Bearer " + owner.token())
                .contentType("application/json")
                .content(store.encode(Map.of("code", code, "confirm", false))))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/auth/web-challenges/confirm")
                .header("Authorization", "Bearer " + owner.token())
                .contentType("application/json")
                .content(store.encode(Map.of("code", code, "confirm", true))))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/auth/web-challenges/confirm")
                .header("Authorization", "Bearer " + other.token())
                .contentType("application/json")
                .content(store.encode(Map.of("code", code, "confirm", true))))
        .andExpect(status().isBadRequest());
    var login =
        mvc.perform(get("/api/v1/auth/web-challenges/" + id).header("X-Login-Poll-Token", poll))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.user.id").value(owner.user()))
            .andReturn()
            .getResponse();
    String token = store.json.readTree(login.getContentAsString()).get("token").asText();
    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(owner.user()));
    mvc.perform(get("/api/v1/auth/web-challenges/" + id).header("X-Login-Poll-Token", poll))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONSUMED"))
        .andExpect(jsonPath("$.token").doesNotExist());
  }

  @Test
  void webLoginCodesExpire() throws Exception {
    Login login = login();
    var response =
        mvc.perform(post("/api/v1/auth/web-challenges"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var challenge = store.json.readTree(response.getContentAsString());
    store.db.update(
        "UPDATE web_login_challenge SET expires_at=0 WHERE id=?", challenge.get("id").asText());
    mvc.perform(
            get("/api/v1/auth/web-challenges/" + challenge.get("id").asText())
                .header("X-Login-Poll-Token", challenge.get("pollToken").asText()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EXPIRED"));
    mvc.perform(
            post("/api/v1/auth/web-challenges/confirm")
                .header("Authorization", "Bearer " + login.token())
                .contentType("application/json")
                .content(
                    store.encode(Map.of("code", challenge.get("code").asText(), "confirm", true))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reconnectCannotSwitchPlatformIdentityOrRestoreRevokedCredentials() throws Exception {
    Login login = login();
    String id = account(login);
    var original = store.owned("platform_account", id, login.user());
    accounts.saveSession(original, new Session("new-token", "external-id", null, null, false));
    accounts.saveSession(
        original, new Session("old-token", "external-id", "old-user", "revoked-password", true));
    assertFalse(
        accounts.session(store.owned("platform_account", id, login.user())).storeCredentials());
    when(connector.login(anyString(), anyString(), anyBoolean()))
        .thenReturn(
            ConnectorResult.success(
                new Session("new-token", "different-external-user", null, null, false)));
    mvc.perform(
            post("/api/v1/accounts")
                .header("Authorization", "Bearer " + login.token())
                .contentType("application/json")
                .content(
                    "{\"platform\":\"COROS\",\"username\":\"different\",\"password\":\"secret\",\"storeCredentials\":false}"))
        .andExpect(status().isBadRequest());
    assertEquals(
        "external-id",
        accounts.session(store.owned("platform_account", id, login.user())).externalUserId());
  }

  @Test
  void webSessionConnectorIsEncryptedNotReturnedAndCanReconnect() throws Exception {
    PlatformConnector web = mock(PlatformConnector.class);
    doReturn(web).when(accounts).connector("STRAVA");
    when(web.connectWeb(any()))
        .thenReturn(
            ConnectorResult.success(
                new Session(
                    null,
                    null,
                    null,
                    null,
                    false,
                    Map.of("cookie", "_strava4_session=very-secret-cookie"))));
    Login login = login();
    String body =
        "{\"platform\":\"STRAVA\",\"displayName\":\"我的 Strava\",\"session\":{"
            + "\"cookie\":\"_strava4_session=very-secret-cookie\"}}";
    var response =
        mvc.perform(
                post("/api/v1/accounts/web-session")
                    .header("Authorization", "Bearer " + login.token())
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.platform").value("STRAVA"))
            .andReturn()
            .getResponse();
    String json = response.getContentAsString();
    assertFalse(json.contains("very-secret-cookie"));
    String account = store.json.readTree(json).get("id").asText();
    var row = store.owned("platform_account", account, login.user());
    assertFalse(CloudStore.str(row, "encrypted_session").contains("very-secret-cookie"));
    assertEquals(
        "very-secret-cookie",
        accounts.session(row).attribute("cookie").replace("_strava4_session=", ""));
    assertEquals(1, accounts.list(login.user()).size());
    mvc.perform(
            post("/api/v1/accounts/web-session")
                .header("Authorization", "Bearer " + login.token())
                .contentType("application/json")
                .content("{\"platform\":\"STRAVA\",\"displayName\":\"另一个账号\",\"session\":{}}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void webSessionConfigAdvertisesFourSupportedPlatforms() throws Exception {
    mvc.perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stravaOAuthConfigured").value(false))
        .andExpect(jsonPath("$.platforms[?(@.code=='COROS')].authMode").value("PASSWORD"))
        .andExpect(jsonPath("$.platforms[?(@.code=='IGPSPORT')].authMode").value("PASSWORD"))
        .andExpect(jsonPath("$.platforms[?(@.code=='STRAVA')].authMode").value("OAUTH"))
        .andExpect(jsonPath("$.platforms[?(@.code=='ONELAP')].authMode").value("PASSWORD"))
        .andExpect(jsonPath("$.platforms[?(@.code=='XINGZHE')].authMode").value("PASSWORD"))
        .andExpect(jsonPath("$.platforms[?(@.code=='GARMIN')].authMode").value("PASSWORD"));
  }

  @Test
  void stravaOAuthFlowPersistsAccountSkipsDestinationPullAndRejectsReplay() throws Exception {
    Login login = login();
    mvc.perform(
            post("/api/v1/accounts/oauth/strava/start")
                .header("Authorization", "Bearer " + login.token())
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of(
                            "clientId",
                            "cid-1",
                            "clientSecret",
                            "csec-1",
                            "redirectUri",
                            "http://127.0.0.1:8080/api/v1/accounts/oauth/strava/callback",
                            "returnUrl",
                            "https://evil.example.com/steal"))))
        .andExpect(status().isBadRequest());

    var badScopeStart =
        store.json.readTree(
            mvc.perform(
                    post("/api/v1/accounts/oauth/strava/start")
                        .header("Authorization", "Bearer " + login.token())
                        .contentType("application/json")
                        .content(
                            store.encode(
                                Map.of(
                                    "clientId",
                                    "cid-1",
                                    "clientSecret",
                                    "csec-1",
                                    "redirectUri",
                                    "http://127.0.0.1:8080/api/v1/accounts/oauth/strava/callback",
                                    "returnUrl",
                                    "http://127.0.0.1:8080/sync"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String badId = badScopeStart.get("challengeId").asText();
    String badState = queryParam(badScopeStart.get("authorizeUrl").asText(), "state");
    mvc.perform(
            get("/api/v1/accounts/oauth/strava/callback")
                .param("state", badState)
                .param("code", "code-bad")
                .param("scope", "read"))
        .andExpect(status().isFound())
        .andExpect(
            header()
                .string(
                    "Location",
                    org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8080/sync?stravaOAuth=error")));
    mvc.perform(
            get("/api/v1/accounts/oauth/strava/" + badId)
                .header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));

    var stravaMock = mock(com.fitsync.cloud.connector.StravaConnector.class);
    when(stravaMock.exchangeOAuthCode(
            eq("cid-1"), eq("csec-1"), eq("code-123"), anyString(), eq(true)))
        .thenReturn(
            ConnectorResult.success(
                new Session(
                    "strava-access-1",
                    "778899",
                    null,
                    null,
                    false,
                    Map.of(
                        "authType", "OAUTH",
                        "refreshToken", "strava-refresh-1",
                        "expiresAt", "2000000000",
                        "scope", "read,activity:read_all,activity:write",
                        "clientId", "cid-1",
                        "clientSecret", "csec-1",
                        "displayName", "Strava Rider"))));
    when(stravaMock.skipPullAsDestination(any())).thenCallRealMethod();
    doReturn(stravaMock).when(accounts).connector("STRAVA");

    var startJson =
        store.json.readTree(
            mvc.perform(
                    post("/api/v1/accounts/oauth/strava/start")
                        .header("Authorization", "Bearer " + login.token())
                        .contentType("application/json")
                        .content(
                            store.encode(
                                Map.of(
                                    "clientId",
                                    "cid-1",
                                    "clientSecret",
                                    "csec-1",
                                    "redirectUri",
                                    "http://127.0.0.1:8080/api/v1/accounts/oauth/strava/callback",
                                    "returnUrl",
                                    "http://127.0.0.1:8080/sync"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String challengeId = startJson.get("challengeId").asText();
    String authorizeUrl = startJson.get("authorizeUrl").asText();
    assertTrue(authorizeUrl.contains("client_id=cid-1"));
    String state = queryParam(authorizeUrl, "state");
    assertFalse(state.isBlank());
    var challengeRow =
        store.one("SELECT status,state_hash,encrypted_context FROM oauth_challenge WHERE id=?", challengeId);
    assertEquals("PENDING", challengeRow.get("status"));
    assertEquals(CryptoService.hash(state), challengeRow.get("state_hash"));
    assertFalse(CloudStore.str(challengeRow, "encrypted_context").contains("csec-1"));

    mvc.perform(
            get("/api/v1/accounts/oauth/strava/callback")
                .param("state", state)
                .param("code", "code-123")
                .param("scope", "read,activity:read_all,activity:write"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "http://127.0.0.1:8080/sync?stravaOAuth=success"));

    mvc.perform(
            get("/api/v1/accounts/oauth/strava/" + challengeId)
                .header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));

    // 同一 state 不可重放。
    mvc.perform(
            get("/api/v1/accounts/oauth/strava/callback")
                .param("state", state)
                .param("code", "code-123")
                .param("scope", "read,activity:read_all,activity:write"))
        .andExpect(status().isBadRequest());
    verify(stravaMock, times(1))
        .exchangeOAuthCode(eq("cid-1"), eq("csec-1"), eq("code-123"), anyString(), eq(true));

    var accountsResp =
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + login.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].platform").value("STRAVA"))
            .andExpect(jsonPath("$[0].displayName").value("Strava Rider"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(accountsResp.contains("csec-1"));
    assertFalse(accountsResp.contains("strava-access-1"));
    assertFalse(accountsResp.contains("strava-refresh-1"));

    String stravaId = store.json.readTree(accountsResp).get(0).get("id").asText();
    var accRow = store.owned("platform_account", stravaId, login.user());
    assertFalse(CloudStore.str(accRow, "encrypted_session").contains("csec-1"));
    assertEquals("strava-refresh-1", accounts.session(accRow).attribute("refreshToken"));

    String activityId = imported(login);
    jobs.rule(login.user(), "LOCAL", stravaId, true, true);
    jobs.scheduleRules();
    jobs.sync(login.user(), "LOCAL", List.of(stravaId), List.of(activityId), 90);
    assertEquals(
        0,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM sync_job WHERE user_id=? AND account_id=? AND kind='PULL'",
            Integer.class,
            login.user(),
            stravaId));
    assertThrows(
        ApiException.class,
        () -> jobs.sync(login.user(), "STRAVA", List.of(), List.of(), 90));
  }

  private static String queryParam(String url, String key) {
    String query = java.net.URI.create(url).getRawQuery();
    if (query == null) return "";
    for (String pair : query.split("&")) {
      int idx = pair.indexOf('=');
      if (idx > 0
          && java.net.URLDecoder.decode(pair.substring(0, idx), java.nio.charset.StandardCharsets.UTF_8)
              .equals(key)) {
        return java.net.URLDecoder.decode(
            pair.substring(idx + 1), java.nio.charset.StandardCharsets.UTF_8);
      }
    }
    return "";
  }

  @Test
  void pendingRuleJobsStopWhenRuleIsDisabled() throws Exception {
    Login login = login();
    String account = account(login), activity = imported(login);
    jobs.rule(login.user(), "LOCAL", account, true, true);
    var row =
        store.one(
            "SELECT id FROM sync_job WHERE user_id=? AND activity_id=?", login.user(), activity);
    jobs.rule(login.user(), "LOCAL", account, false, true);
    jobs.runOne(CloudStore.str(row, "id"));
    assertEquals(
        "CANCELLED",
        store.one("SELECT status FROM sync_job WHERE id=?", row.get("id")).get("status"));
    verify(connector, never()).uploadFit(any(), any(), any());
  }

  @Test
  void higherQualityPrimaryCannotCreateSecondUploadWhileFirstIsUnconfirmed() throws Exception {
    Login login = login();
    String account = account(login),
        activity = imported(login),
        first = jobs.delivery(login.user(), activity, account);
    var original =
        store.one(
            "SELECT * FROM activity_variant WHERE activity_id=? AND user_id=?",
            activity,
            login.user());
    String next = CloudStore.id();
    store.db.update(
        "INSERT INTO"
            + " activity_variant(id,user_id,activity_id,sha256,file_path,metadata_json,quality_score)"
            + " VALUES(?,?,?,?,?,?,?)",
        next,
        login.user(),
        activity,
        "b".repeat(64),
        original.get("file_path"),
        original.get("metadata_json"),
        100);
    store.db.update("UPDATE activity SET primary_variant_id=? WHERE id=?", next, activity);
    assertEquals(2L, activities.detail(login.user(), activity).get("variantCount"));
    assertEquals(first, jobs.delivery(login.user(), activity, account));
    assertEquals(
        original.get("id"),
        store
            .decode(
                CloudStore.str(
                    store.one("SELECT payload_json FROM sync_job WHERE id=?", first),
                    "payload_json"))
            .get("variantId"));
    when(connector.uploadFit(any(), any(), any()))
        .thenReturn(ConnectorResult.processing("confirm-original"));
    jobs.runOne(first);
    assertEquals(first, jobs.delivery(login.user(), activity, account));
    verify(connector, times(1)).uploadFit(any(), any(), any());
    assertEquals(
        1,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM sync_job WHERE activity_id=? AND account_id=?",
            Integer.class,
            activity,
            account));
  }

  @Test
  void manualHistoryUsesRequestedWindowAndPullOnlyNeedsNoDestination() throws Exception {
    Login login = login();
    String account = account(login);
    store.db.update(
        "UPDATE platform_account SET last_sync_at=? WHERE id=?",
        System.currentTimeMillis(),
        account);
    long before = System.currentTimeMillis();
    var result = jobs.sync(login.user(), "COROS", List.of(), List.of(), 90);
    String id = ((List<?>) result.get("jobIds")).getFirst().toString();
    long since =
        CloudStore.number(
            store.decode(
                CloudStore.str(
                    store.one("SELECT payload_json FROM sync_job WHERE id=?", id), "payload_json")),
            "since");
    assertTrue(since <= before - java.time.Duration.ofDays(89).toMillis());
  }

  @Test
  void ruleRecognizesCloudSourceCopyEvenWhenCanonicalWasImportedLocally() throws Exception {
    Login login = login();
    String source = account(login), activity = imported(login);
    activities.mapCopy(login.user(), activity, source, "existing-coros-copy");
    doReturn(connector).when(accounts).connector("IGPSPORT");
    var response =
        mvc.perform(
                post("/api/v1/accounts")
                    .header("Authorization", "Bearer " + login.token())
                    .contentType("application/json")
                    .content(
                        "{\"platform\":\"IGPSPORT\",\"username\":\"other-platform\",\"password\":\"secret\",\"storeCredentials\":false}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    String destination = store.json.readTree(response.getContentAsString()).get("id").asText();
    jobs.rule(login.user(), "COROS", destination, true, true);
    assertEquals(
        1,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM sync_job WHERE activity_id=? AND account_id=? AND"
                + " kind='DELIVERY'",
            Integer.class,
            activity,
            destination));
  }

  @Test
  void keepingActivitySeparateIsOwnedIdempotentAndResumesRules() throws Exception {
    Login login = login(), other = login();
    String account = account(login), activity = imported(login);
    store.db.update(
        "UPDATE activity SET needs_review=TRUE,review_candidate_id=? WHERE id=?",
        activity,
        activity);
    jobs.rule(login.user(), "LOCAL", account, true, true);
    assertEquals(
        0,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM sync_job WHERE activity_id=?", Integer.class, activity));
    mvc.perform(
            post("/api/v1/activities/" + activity + "/review")
                .header("Authorization", "Bearer " + other.token())
                .contentType("application/json")
                .content("{\"decision\":\"KEEP_SEPARATE\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/activities/" + activity + "/review")
                .header("Authorization", "Bearer " + login.token())
                .contentType("application/json")
                .content("{\"decision\":\"KEEP_SEPARATE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.needsReview").value(false))
        .andExpect(jsonPath("$.alreadyReviewed").value(false));
    mvc.perform(
            post("/api/v1/activities/" + activity + "/review")
                .header("Authorization", "Bearer " + login.token())
                .contentType("application/json")
                .content("{\"decision\":\"KEEP_SEPARATE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alreadyReviewed").value(true));
    var reviewed = store.owned("activity", activity, login.user());
    assertFalse((Boolean) reviewed.get("needs_review"));
    assertNull(reviewed.get("review_candidate_id"));
    assertEquals(
        1,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM sync_job WHERE activity_id=? AND account_id=?",
            Integer.class,
            activity,
            account));
  }

  @Test
  void duplicateEnqueueUsesSavepointAndParticipatesInOuterRollback() throws Exception {
    Login login = login();
    String account = account(login), activity = imported(login);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              String first = jobs.delivery(login.user(), activity, account);
              assertEquals(first, jobs.delivery(login.user(), activity, account));
              assertEquals(
                  1,
                  store.db.queryForObject(
                      "SELECT COUNT(*) FROM sync_job WHERE activity_id=? AND account_id=?",
                      Integer.class,
                      activity,
                      account));
              status.setRollbackOnly();
            });
    assertEquals(
        0,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM sync_job WHERE activity_id=? AND account_id=?",
            Integer.class,
            activity,
            account));
  }

  @Test
  void dashboardAggregatesOnlyOwnedDataAndOmitsLastSyncUntilFirstSync() throws Exception {
    Login alice = login(), bob = login();
    String activity = imported(alice), account = account(alice);
    jobs.delivery(alice.user(), activity, account);
    imported(bob);

    mvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activityCount").value(1))
        .andExpect(jsonPath("$.totalDistanceMeters").value(42195.0))
        .andExpect(jsonPath("$.pendingJobs").value(1))
        .andExpect(jsonPath("$.connectedAccounts").value(1))
        .andExpect(jsonPath("$.lastSyncAt").doesNotExist());
    mvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer " + bob.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activityCount").value(1))
        .andExpect(jsonPath("$.connectedAccounts").value(0))
        .andExpect(jsonPath("$.pendingJobs").value(0));
    store.db.update(
        "UPDATE platform_account SET last_sync_at=? WHERE id=?",
        1_700_000_000_000L,
        account);
    mvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastSyncAt").value(1_700_000_000_000L));
  }

  @Test
  void logoutRevokesOnlyTheSessionItWasCalledWith() throws Exception {
    Login login = login();
    String other = "other-device-token";
    store.db.update(
        "INSERT INTO user_session(token_hash,user_id,expires_at) VALUES(?,?,?)",
        CryptoService.hash(other),
        login.user(),
        System.currentTimeMillis() + 86_400_000L);
    mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + other))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(login.user()));
  }

  @Test
  void pullFromConnectedPlatformsDeduplicatesStoresInCloudAndSkipsPermanentMissingFiles()
      throws Exception {
    Login alice = login(), bob = login();
    String corosAccount = account(alice);

    PlatformConnector igpsport = mock(PlatformConnector.class);
    doReturn(igpsport).when(accounts).connector("IGPSPORT");
    when(igpsport.login(anyString(), anyString(), anyBoolean()))
        .thenReturn(
            ConnectorResult.success(
                new Session("igp-token", "igp-user-1", "igp@example.com", "pw", true)));
    String igpResp =
        mvc.perform(
                post("/api/v1/accounts")
                    .header("Authorization", "Bearer " + alice.token())
                    .contentType("application/json")
                    .content(
                        "{\"platform\":\"IGPSPORT\",\"username\":\"igp@example.com\",\"password\":\"very-secret-pw\",\"storeCredentials\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pullSupported").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String igpAccount = store.json.readTree(igpResp).get("id").asText();

    byte[] corosRide = rideFit(10_000, 2, 3_600_000, 3_500_000, 4_219_500, 148, 1);
    byte[] igpSameRide = rideFit(10_020, 2, 3_602_000, 3_500_000, 4_220_000, 150, 200);
    byte[] corosRun = rideFit(50_000, 1, 1_800_000, 1_780_000, 1_000_000, 155, 1);
    assertNotEquals(FitParser.sha256(corosRide), FitParser.sha256(igpSameRide));

    when(connector.listActivities(any(), anyLong(), any()))
        .thenReturn(
            ConnectorResult.success(
                new ActivityPage(
                    List.of(
                        new RemoteActivity("coros-ride-1", 631_075_600_000L, "周日西湖拉练", 42195.0, null, Map.of()),
                        new RemoteActivity("coros-missing-1", 631_076_000_000L, "无原文件活动", 5000.0, null, Map.of()),
                        new RemoteActivity("coros-run-1", 631_115_600_000L, "奥森 10K 晨跑", 10000.0, null, Map.of())),
                    null,
                    false)));
    when(connector.downloadFit(any(), argThat(a -> a != null && "coros-ride-1".equals(a.remoteActivityId()))))
        .thenReturn(ConnectorResult.success(new FitDownload(corosRide, "coros-ride-1.fit")));
    when(connector.downloadFit(any(), argThat(a -> a != null && "coros-missing-1".equals(a.remoteActivityId()))))
        .thenReturn(ConnectorResult.failure(ConnectorResult.Status.PERMANENT_FAILURE, "NO_ORIGINAL_FILE", "COROS 未保留该活动的 FIT"));
    when(connector.downloadFit(any(), argThat(a -> a != null && "coros-run-1".equals(a.remoteActivityId()))))
        .thenReturn(ConnectorResult.success(new FitDownload(corosRun, "coros-run-1.fit")));

    when(igpsport.listActivities(any(), anyLong(), any()))
        .thenReturn(
            ConnectorResult.success(
                new ActivityPage(
                    List.of(
                        new RemoteActivity("igp-ride-1", 631_075_620_000L, "IGP 同步骑行", 42200.0, null, Map.of())),
                    null,
                    false)));
    when(igpsport.downloadFit(any(), argThat(a -> a != null && "igp-ride-1".equals(a.remoteActivityId()))))
        .thenReturn(ConnectorResult.success(new FitDownload(igpSameRide, "igp-ride-1.fit")));

    String pullStartJson =
        mvc.perform(
                post("/api/v1/activities/pull")
                    .header("Authorization", "Bearer " + alice.token())
                    .contentType("application/json")
                    .content("{\"historyDays\":90}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accounts.length()").value(2))
            .andExpect(jsonPath("$.skippedPlatforms.length()").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var pullTree = store.json.readTree(pullStartJson);
    List<String> jobIds = new ArrayList<>();
    for (var n : pullTree.get("jobIds")) jobIds.add(n.asText());
    assertEquals(2, jobIds.size());

    // 在同步执行前再次点击拉取会复用活跃任务而不重复排队。
    String secondStart =
        mvc.perform(
                post("/api/v1/activities/pull")
                    .header("Authorization", "Bearer " + alice.token())
                    .contentType("application/json")
                    .content("{\"historyDays\":90}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertEquals(
        Set.copyOf(jobIds),
        Set.of(
            store.json.readTree(secondStart).get("jobIds").get(0).asText(),
            store.json.readTree(secondStart).get("jobIds").get(1).asText()));

    for (String jid : jobIds) jobs.runOne(jid);

    mvc.perform(
            get("/api/v1/activities/pull")
                .param("jobIds", String.join(",", jobIds))
                .header("Authorization", "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeCount").value(0))
        .andExpect(jsonPath("$.totals.fetched").value(4))
        .andExpect(jsonPath("$.totals.imported").value(2))
        .andExpect(jsonPath("$.totals.merged").value(1))
        .andExpect(jsonPath("$.totals.skippedNoFit").value(1));

    // 其他用户无法查看 alice 的拉取任务。
    mvc.perform(
            get("/api/v1/activities/pull")
                .param("jobIds", String.join(",", jobIds))
                .header("Authorization", "Bearer " + bob.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobs.length()").value(0));

    // 3 条远端有效活动 + 1 条无原文件：跨平台去重合并后云端共保存 2 条规范活动。
    String listJson =
        mvc.perform(get("/api/v1/activities").header("Authorization", "Bearer " + alice.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items[0].title").value("奥森 10K 晨跑"))
            .andExpect(jsonPath("$.items[1].title").value("周日西湖拉练"))
            .andExpect(jsonPath("$.items[1].variantCount").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var listTree = store.json.readTree(listJson);
    String mergedRideId = listTree.get("items").get(1).get("id").asText();
    var ridePlatforms = new HashSet<String>();
    for (var p : listTree.get("items").get(1).get("platforms")) ridePlatforms.add(p.asText());
    assertEquals(Set.of("COROS", "IGPSPORT"), ridePlatforms);
    assertTrue(listTree.get("items").get(1).get("track").size() >= 2);

    // 活动详情包含两个来源 FIT 副本与两个平台映射，且可从云端导出原始 FIT。
    mvc.perform(
            get("/api/v1/activities/" + mergedRideId)
                .header("Authorization", "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.variants.length()").value(2))
        .andExpect(jsonPath("$.copies.length()").value(2));
    mvc.perform(
            get("/api/v1/activities/" + mergedRideId + "/file")
                .header("Authorization", "Bearer " + alice.token()))
        .andExpect(status().isOk());

    // 再次拉取时，已同步活动与 remote_activity_skip 均跳过，不重复下载 FIT。
    String pullAgainJson =
        mvc.perform(
                post("/api/v1/activities/pull")
                    .header("Authorization", "Bearer " + alice.token())
                    .contentType("application/json")
                    .content("{\"accountIds\":[\"" + corosAccount + "\",\"" + igpAccount + "\"]}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> againJobIds = new ArrayList<>();
    for (var n : store.json.readTree(pullAgainJson).get("jobIds")) againJobIds.add(n.asText());
    for (String jid : againJobIds) jobs.runOne(jid);
    verify(connector, times(3)).downloadFit(any(), any());
    verify(igpsport, times(1)).downloadFit(any(), any());

    mvc.perform(
            get("/api/v1/activities/pull")
                .param("jobIds", String.join(",", againJobIds))
                .header("Authorization", "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totals.existingSkipped").value(4))
        .andExpect(jsonPath("$.totals.imported").value(0))
        .andExpect(jsonPath("$.totals.merged").value(0));

    // 解绑带 remote_activity_skip 的账号不会触发外键约束错误，且云端活动保留。
    mvc.perform(
            delete("/api/v1/accounts/" + corosAccount)
                .header("Authorization", "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
    mvc.perform(get("/api/v1/activities").header("Authorization", "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2));
  }

  @Test
  void pullSkipsStravaOAuthGracefullyAndDrainsMultiplePagesInSingleRun() throws Exception {
    Login login = login();
    account(login);
    var stravaMock = mock(StravaConnector.class);
    when(stravaMock.skipPullAsDestination(any())).thenCallRealMethod();
    doReturn(stravaMock).when(accounts).connector("STRAVA");
    accounts.persistOAuth(
        login.user(),
        "STRAVA",
        "Strava Rider",
        new Session(
            "strava-access",
            "778899",
            null,
            null,
            false,
            Map.of("authType", "OAUTH", "refreshToken", "strava-refresh")));

    byte[] p1Ride = rideFit(20_000, 2, 3_600_000, 3_500_000, 3_000_000, 140, 1);
    byte[] p2Ride = rideFit(40_000, 2, 3_600_000, 3_500_000, 3_500_000, 145, 1);
    when(connector.listActivities(any(), anyLong(), isNull()))
        .thenReturn(
            ConnectorResult.success(
                new ActivityPage(
                    List.of(new RemoteActivity("p1-1", 631_085_600_000L, "第一页骑行", 30000.0, null, Map.of())),
                    "2",
                    true)));
    when(connector.listActivities(any(), anyLong(), eq("2")))
        .thenReturn(
            ConnectorResult.success(
                new ActivityPage(
                    List.of(new RemoteActivity("p2-1", 631_105_600_000L, "第二页骑行", 35000.0, null, Map.of())),
                    null,
                    false)));
    when(connector.downloadFit(any(), argThat(a -> a != null && "p1-1".equals(a.remoteActivityId()))))
        .thenReturn(ConnectorResult.success(new FitDownload(p1Ride, "p1.fit")));
    when(connector.downloadFit(any(), argThat(a -> a != null && "p2-1".equals(a.remoteActivityId()))))
        .thenReturn(ConnectorResult.success(new FitDownload(p2Ride, "p2.fit")));

    // 单独请求 Strava OAuth 拉取被明确拒绝；全量拉取时自动跳过 Strava 仅拉取 COROS。
    mvc.perform(
            post("/api/v1/activities/pull")
                .header("Authorization", "Bearer " + login.token())
                .contentType("application/json")
                .content("{\"platforms\":[\"STRAVA\"]}"))
        .andExpect(status().isBadRequest());

    String pullJson =
        mvc.perform(
                post("/api/v1/activities/pull")
                    .header("Authorization", "Bearer " + login.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accounts.length()").value(1))
            .andExpect(jsonPath("$.accounts[0].platform").value("COROS"))
            .andExpect(jsonPath("$.skippedPlatforms.length()").value(1))
            .andExpect(jsonPath("$.skippedPlatforms[0].platform").value("STRAVA"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String jobId = store.json.readTree(pullJson).get("jobIds").get(0).asText();
    jobs.runOne(jobId);

    mvc.perform(
            get("/api/v1/activities/pull")
                .param("jobIds", jobId)
                .header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobs[0].status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.totals.pages").value(2))
        .andExpect(jsonPath("$.totals.imported").value(2));
  }

  @Test
  void pullResumesFromSavedCursorAfterTransientFailure() throws Exception {
    Login login = login();
    account(login);
    byte[] p1Ride = rideFit(20_000, 2, 3_600_000, 3_500_000, 3_000_000, 140, 1);
    byte[] p2Ride = rideFit(40_000, 2, 3_600_000, 3_500_000, 3_500_000, 145, 1);
    when(connector.listActivities(any(), anyLong(), isNull()))
        .thenReturn(
            ConnectorResult.success(
                new ActivityPage(
                    List.of(new RemoteActivity("p1-1", 631_085_600_000L, "第一页骑行", 30000.0, null, Map.of())),
                    "2",
                    true)));
    when(connector.listActivities(any(), anyLong(), eq("2")))
        .thenReturn(ConnectorResult.failure(ConnectorResult.Status.RETRYABLE_FAILURE, "HTTP_503", "平台限流"))
        .thenReturn(
            ConnectorResult.success(
                new ActivityPage(
                    List.of(new RemoteActivity("p2-1", 631_105_600_000L, "第二页骑行", 35000.0, null, Map.of())),
                    null,
                    false)));
    when(connector.downloadFit(any(), argThat(a -> a != null && "p1-1".equals(a.remoteActivityId()))))
        .thenReturn(ConnectorResult.success(new FitDownload(p1Ride, "p1.fit")));
    when(connector.downloadFit(any(), argThat(a -> a != null && "p2-1".equals(a.remoteActivityId()))))
        .thenReturn(ConnectorResult.success(new FitDownload(p2Ride, "p2.fit")));

    String pullJson =
        mvc.perform(
                post("/api/v1/activities/pull").header("Authorization", "Bearer " + login.token()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String jobId = store.json.readTree(pullJson).get("jobIds").get(0).asText();
    jobs.runOne(jobId);

    var midRow = store.one("SELECT status,payload_json FROM sync_job WHERE id=?", jobId);
    assertEquals("RETRY", midRow.get("status"));
    assertEquals("2", store.decode(CloudStore.str(midRow, "payload_json")).get("cursor"));

    store.db.update("UPDATE sync_job SET next_run_at=0 WHERE id=?", jobId);
    jobs.runOne(jobId);

    verify(connector, times(1)).listActivities(any(), anyLong(), isNull());
    verify(connector, times(2)).listActivities(any(), anyLong(), eq("2"));
    mvc.perform(
            get("/api/v1/activities/pull")
                .param("jobIds", jobId)
                .header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobs[0].status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.totals.pages").value(2))
        .andExpect(jsonPath("$.totals.imported").value(2));
  }

  @Test
  void activityListFiltersMergeIntoCandidateAndDeleteWorkEndToEnd() throws Exception {
    Login login = login(), other = login();
    String corosAccount = account(login);
    byte[] rideBase = rideFit(10_000, 2, 3_600_000, 3_500_000, 4_219_500, 148, 1);
    byte[] rideSimilar = rideFit(10_240, 2, 3_250_000, 3_150_000, 3_950_000, 149, 200);
    byte[] run = rideFit(50_000, 1, 1_800_000, 1_750_000, 1_000_000, 155, 1);

    var r1 = activities.importFit(login.user(), rideBase, "COROS", corosAccount, "c-1", "晨骑太湖");
    var r2 = activities.importFit(login.user(), rideSimilar, "LOCAL", null, null, null);
    var r3 = activities.importFit(login.user(), run, "LOCAL", null, null, "傍晚慢跑");

    String candidateId = r1.get("activityId").toString();
    String reviewId = r2.get("activityId").toString();
    String runId = r3.get("activityId").toString();
    assertEquals("NEEDS_REVIEW", r2.get("dedupAction"));
    assertEquals(true, r2.get("needsReview"));

    mvc.perform(
            get("/api/v1/activities")
                .param("filter", "RUNNING")
                .header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].id").value(runId));

    mvc.perform(
            get("/api/v1/activities")
                .param("filter", "REVIEW")
                .header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].id").value(reviewId));

    mvc.perform(
            get("/api/v1/activities")
                .param("query", "太湖")
                .param("platform", "COROS")
                .header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].id").value(candidateId));

    mvc.perform(
            get("/api/v1/activities")
                .param("sort", "OLDEST")
                .header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(candidateId));

    // 合并到候选活动：迁移副本并移除重复活动记录。
    mvc.perform(
            post("/api/v1/activities/" + reviewId + "/review")
                .header("Authorization", "Bearer " + login.token())
                .contentType("application/json")
                .content("{\"decision\":\"MERGE_INTO_CANDIDATE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activityId").value(candidateId))
        .andExpect(jsonPath("$.merged").value(true))
        .andExpect(jsonPath("$.movedVariants").value(1));

    mvc.perform(
            get("/api/v1/activities/" + reviewId)
                .header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/activities/" + candidateId)
                .header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.variantCount").value(2));

    // 非所有者不可删除，所有者删除后磁盘文件与记录均清理。
    mvc.perform(
            delete("/api/v1/activities/" + runId)
                .header("Authorization", "Bearer " + other.token()))
        .andExpect(status().isNotFound());
    mvc.perform(
            delete("/api/v1/activities/" + runId)
                .header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
    mvc.perform(
            get("/api/v1/activities/" + runId)
                .header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/activities/" + runId + "/file")
                .header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isNotFound());
  }

  @Test
  void manualSyncAndRulesFilterBySportTypesEndToEnd() throws Exception {
    Login login = login();
    PlatformConnector garmin = mock(PlatformConnector.class);
    PlatformConnector onelap = mock(PlatformConnector.class);
    doReturn(garmin).when(accounts).connector("GARMIN");
    doReturn(onelap).when(accounts).connector("ONELAP");
    when(garmin.login(anyString(), anyString(), anyBoolean()))
        .thenReturn(ConnectorResult.success(new Session("g-tok", "g-1", "g@ex.com", "pw", true)));
    when(onelap.login(anyString(), anyString(), anyBoolean()))
        .thenReturn(ConnectorResult.success(new Session("o-tok", "o-1", "o@ex.com", "pw", true)));

    String garminResp =
        mvc.perform(
                post("/api/v1/accounts")
                    .header("Authorization", "Bearer " + login.token())
                    .contentType("application/json")
                    .content(
                        "{\"platform\":\"GARMIN\",\"username\":\"g@ex.com\",\"password\":\"pw\",\"storeCredentials\":true}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String onelapResp =
        mvc.perform(
                post("/api/v1/accounts")
                    .header("Authorization", "Bearer " + login.token())
                    .contentType("application/json")
                    .content(
                        "{\"platform\":\"ONELAP\",\"username\":\"o@ex.com\",\"password\":\"pw\",\"storeCredentials\":true}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String garminAcc = store.json.readTree(garminResp).get("id").asText();
    String onelapAcc = store.json.readTree(onelapResp).get("id").asText();

    int recentSec = (int) ((System.currentTimeMillis() - 631_065_600_000L) / 1000L) - 7200;
    long recentMs = 631_065_600_000L + recentSec * 1000L;
    byte[] garminNewRide = rideFit(recentSec, 2, 3_600_000, 3_500_000, 3_000_000, 145, 1);
    byte[] garminAmbiguousRun = rideFit(recentSec + 600, 1, 1_800_000, 1_750_000, 800_000, 155, 1);
    byte[] garminKnownRide = rideFit(recentSec - 3600, 2, 2_400_000, 2_300_000, 2_000_000, 142, 1);
    byte[] garminKnownRun = rideFit(recentSec - 7200, 1, 1_500_000, 1_450_000, 500_000, 152, 1);

    // 预先存在两条已从 Garmin 拉取到云端、尚未投递到迈金的活动（一条骑行、一条跑步）。
    var existingRideRes =
        activities.importFit(
            login.user(), garminKnownRide, "GARMIN", garminAcc, "g-old-ride", "旧骑行");
    var existingRunRes =
        activities.importFit(
            login.user(), garminKnownRun, "GARMIN", garminAcc, "g-old-run", "旧跑步");
    String existingRideId = existingRideRes.get("activityId").toString();
    String existingRunId = existingRunRes.get("activityId").toString();

    when(onelap.listActivities(any(), anyLong(), any()))
        .thenReturn(ConnectorResult.success(new ActivityPage(List.of(), null, false)));
    when(garmin.listActivities(any(), anyLong(), any()))
        .thenReturn(
            ConnectorResult.success(
                new ActivityPage(
                    List.of(
                        new RemoteActivity(
                            "g-old-ride",
                            recentMs - 3_600_000,
                            "旧骑行",
                            20000.0,
                            null,
                            Map.of("sport_type", "road_biking")),
                        new RemoteActivity(
                            "g-old-run",
                            recentMs - 7_200_000,
                            "旧跑步",
                            5000.0,
                            null,
                            Map.of("sport_type", "running")),
                        new RemoteActivity(
                            "g-new-ride",
                            recentMs,
                            "周末公路骑行",
                            30000.0,
                            null,
                            Map.of("sport_type", "road_biking")),
                        new RemoteActivity(
                            "g-new-run",
                            recentMs + 300_000,
                            "晨间路跑",
                            10000.0,
                            null,
                            Map.of("sport_type", "running")),
                        new RemoteActivity(
                            "g-unknown-run",
                            recentMs + 600_000,
                            "未分类跑",
                            8000.0,
                            null,
                            Map.of("sport_type", "uncategorized"))),
                    null,
                    false)));
    when(garmin.downloadFit(any(), argThat(a -> a != null && "g-new-ride".equals(a.remoteActivityId()))))
        .thenReturn(ConnectorResult.success(new FitDownload(garminNewRide, "g-new-ride.fit")));
    when(garmin.downloadFit(
            any(), argThat(a -> a != null && "g-unknown-run".equals(a.remoteActivityId()))))
        .thenReturn(
            ConnectorResult.success(new FitDownload(garminAmbiguousRun, "g-unknown-run.fit")));
    when(onelap.uploadFit(any(), any(), any()))
        .thenReturn(ConnectorResult.success(new UploadResult("onelap-1")), ConnectorResult.success(new UploadResult("onelap-2")));

    // 校验空活动类型与无效活动类型均返回 400。
    mvc.perform(
            post("/api/v1/sync")
                .header("Authorization", "Bearer " + login.token())
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of(
                            "sourcePlatform",
                            "GARMIN",
                            "destinationAccountIds",
                            List.of(onelapAcc),
                            "historyDays",
                            90,
                            "sportTypes",
                            List.of()))))
        .andExpect(status().isBadRequest());

    String syncJson =
        mvc.perform(
                post("/api/v1/sync")
                    .header("Authorization", "Bearer " + login.token())
                    .contentType("application/json")
                    .content(
                        store.encode(
                            Map.of(
                                "sourcePlatform",
                                "GARMIN",
                                "destinationAccountIds",
                                List.of(onelapAcc),
                                "historyDays",
                                90,
                                "sportTypes",
                                List.of("CYCLING")))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (var idNode : store.json.readTree(syncJson).get("jobIds")) {
      jobs.runOne(idNode.asText());
    }
    // 跑完所有生成的 DELIVERY 任务。
    for (var deliveryJob :
        store.db.queryForList(
            "SELECT id FROM sync_job WHERE user_id=? AND kind='DELIVERY'", login.user())) {
      jobs.runOne(CloudStore.str(deliveryJob, "id"));
    }

    // 清晰的 running 活动不会下载 FIT；uncategorized 下载后经 FitParser 判定为 RUNNING，也不投递到迈金。
    verify(garmin, never())
        .downloadFit(any(), argThat(a -> a != null && "g-new-run".equals(a.remoteActivityId())));
    verify(garmin, times(1))
        .downloadFit(any(), argThat(a -> a != null && "g-new-ride".equals(a.remoteActivityId())));
    verify(garmin, times(1))
        .downloadFit(
            any(), argThat(a -> a != null && "g-unknown-run".equals(a.remoteActivityId())));

    // 只有两场骑行（旧骑行 + 新骑行）生成并完成了对迈金的投递；跑步活动未投递。
    var deliveredIds =
        store.db.queryForList(
                "SELECT activity_id FROM sync_job WHERE user_id=? AND kind='DELIVERY' AND"
                    + " account_id=?",
                login.user(),
                onelapAcc)
            .stream()
            .map(r -> CloudStore.str(r, "activity_id"))
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(2, deliveredIds.size());
    assertTrue(deliveredIds.contains(existingRideId));
    assertFalse(deliveredIds.contains(existingRunId));

    // 本地源手动同步同样按 sportTypes 过滤。
    byte[] localRide = rideFit(recentSec - 10800, 2, 1_800_000, 1_750_000, 1_500_000, 138, 1);
    byte[] localRun = rideFit(recentSec - 14400, 1, 1_200_000, 1_150_000, 400_000, 150, 1);
    String localRideId =
        activities.importFit(login.user(), localRide, "LOCAL", null, null, "本地骑行")
            .get("activityId")
            .toString();
    String localRunId =
        activities.importFit(login.user(), localRun, "LOCAL", null, null, "本地跑步")
            .get("activityId")
            .toString();
    mvc.perform(
            post("/api/v1/sync")
                .header("Authorization", "Bearer " + login.token())
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of(
                            "sourcePlatform",
                            "LOCAL",
                            "destinationAccountIds",
                            List.of(onelapAcc),
                            "historyDays",
                            90,
                            "sportTypes",
                            List.of("CYCLING")))))
        .andExpect(status().isOk());
    var localDelivered =
        store.db.queryForList(
                "SELECT activity_id FROM sync_job WHERE user_id=? AND kind='DELIVERY' AND"
                    + " account_id=?",
                login.user(),
                onelapAcc)
            .stream()
            .map(r -> CloudStore.str(r, "activity_id"))
            .collect(java.util.stream.Collectors.toSet());
    assertTrue(localDelivered.contains(localRideId));
    assertFalse(localDelivered.contains(localRunId));

    // 自动同步规则支持 sportTypes，并在开关切换时保留原配置、仅投递匹配类型的活动。
    mvc.perform(
            post("/api/v1/rules")
                .header("Authorization", "Bearer " + login.token())
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of(
                            "sourcePlatform",
                            "LOCAL",
                            "destinationAccountId",
                            garminAcc,
                            "enabled",
                            true,
                            "includeHistory",
                            true,
                            "sportTypes",
                            List.of("CYCLING")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sportTypes.length()").value(1))
        .andExpect(jsonPath("$.sportTypes[0]").value("CYCLING"));
    var ruleDelivered =
        store.db.queryForList(
                "SELECT activity_id FROM sync_job WHERE user_id=? AND kind='DELIVERY' AND"
                    + " account_id=?",
                login.user(),
                garminAcc)
            .stream()
            .map(r -> CloudStore.str(r, "activity_id"))
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(Set.of(localRideId), ruleDelivered);

    // 未传 sportTypes 更新 enabled 时保留规则原有的 CYCLING 限制。
    jobs.rule(login.user(), "LOCAL", garminAcc, false, true);
    mvc.perform(get("/api/v1/rules").header("Authorization", "Bearer " + login.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].enabled").value(false))
        .andExpect(jsonPath("$[0].sportTypes.length()").value(1))
        .andExpect(jsonPath("$[0].sportTypes[0]").value("CYCLING"));
  }

  private static byte[] rideFit(
      int startTimeSec,
      int sport,
      int elapsedMs,
      int movingMs,
      int distanceCm,
      int avgHr,
      int manufacturer) {
    var body = new ByteArrayOutputStream();
    // Local 0: file_id (global 0), fields: type(0,1,0), manufacturer(1,2,0x84)
    body.writeBytes(new byte[] {0x40, 0, 0, 0, 0, 2, 0, 1, 0, 1, 2, (byte) 0x84});
    body.write(0);
    body.write(4);
    body.writeBytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) manufacturer).array());

    // Local 2: record (global 20), fields: lat(0,4,0x85), lon(1,4,0x85), timestamp(253,4,0x86)
    body.writeBytes(
        new byte[] {0x42, 0, 0, 20, 0, 3, 0, 4, (byte) 0x85, 1, 4, (byte) 0x85, (byte) 253, 4, (byte) 0x86});
    for (int i = 0; i < 2; i++) {
      body.write(2);
      body.writeBytes(
          ByteBuffer.allocate(4)
              .order(ByteOrder.LITTLE_ENDIAN)
              .putInt(360_000_000 + i * 50_000)
              .array());
      body.writeBytes(
          ByteBuffer.allocate(4)
              .order(ByteOrder.LITTLE_ENDIAN)
              .putInt(1_430_000_000 + i * 50_000)
              .array());
      body.writeBytes(
          ByteBuffer.allocate(4)
              .order(ByteOrder.LITTLE_ENDIAN)
              .putInt(startTimeSec + i * 600)
              .array());
    }

    // Local 1: session (global 18)
    body.writeBytes(
        new byte[] {
          0x41,
          0,
          0,
          18,
          0,
          6,
          2,
          4,
          (byte) 0x86,
          5,
          1,
          0,
          7,
          4,
          (byte) 0x86,
          8,
          4,
          (byte) 0x86,
          9,
          4,
          (byte) 0x86,
          16,
          1,
          2
        });
    body.write(1);
    body.writeBytes(
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(startTimeSec).array());
    body.write(sport);
    for (int value : new int[] {elapsedMs, movingMs, distanceCm})
      body.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    body.write(avgHr);

    byte[] data = body.toByteArray(), bytes = new byte[14 + data.length + 2];
    var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    buffer
        .put((byte) 14)
        .put((byte) 0x10)
        .putShort((short) 2100)
        .putInt(data.length)
        .put(new byte[] {'.', 'F', 'I', 'T'});
    buffer.putShort((short) crc(bytes, 12));
    buffer.put(data).putShort((short) crc(bytes, bytes.length - 2));
    return bytes;
  }

  private static byte[] fit() {
    var body = new ByteArrayOutputStream();
    body.writeBytes(new byte[] {0x40, 0, 0, 0, 0, 1, 0, 1, 0, 0, 4});
    body.writeBytes(
        new byte[] {
          0x41,
          0,
          0,
          18,
          0,
          6,
          2,
          4,
          (byte) 0x86,
          5,
          1,
          0,
          7,
          4,
          (byte) 0x86,
          8,
          4,
          (byte) 0x86,
          9,
          4,
          (byte) 0x86,
          16,
          1,
          2
        });
    body.write(1);
    body.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1000).array());
    body.write(2);
    for (int value : new int[] {3600000, 3500000, 4219500})
      body.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    body.write(148);
    byte[] data = body.toByteArray(), bytes = new byte[14 + data.length + 2];
    var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    buffer
        .put((byte) 14)
        .put((byte) 0x10)
        .putShort((short) 2100)
        .putInt(data.length)
        .put(new byte[] {'.', 'F', 'I', 'T'});
    buffer.putShort((short) crc(bytes, 12));
    buffer.put(data).putShort((short) crc(bytes, bytes.length - 2));
    return bytes;
  }

  @Test
  void confirmingDeliveriesReconcileBatchWhenPlatformReencodesOrOmitsOriginalFit()
      throws Exception {
    Login login = login();
    String account = account(login);
    long fitEpochOffsetMs = 631_065_600_000L;
    byte[] fitA = rideFit(100_000, 2, 3600_000, 3500_000, 3250_000, 145, 1);
    byte[] fitB = rideFit(200_000, 2, 1800_000, 1750_000, 1800_000, 140, 1);
    byte[] fitC = rideFit(300_000, 2, 2400_000, 2300_000, 2100_000, 142, 1);
    String actA =
        activities
            .importFit(login.user(), fitA, "LOCAL", null, null, "Mahdha 虚拟骑行")
            .get("activityId")
            .toString();
    String actB =
        activities
            .importFit(login.user(), fitB, "LOCAL", null, null, "Dubai 虚拟骑行")
            .get("activityId")
            .toString();
    String actC =
        activities
            .importFit(login.user(), fitC, "LOCAL", null, null, "Dubai 虚拟骑行")
            .get("activityId")
            .toString();
    String jobA = jobs.delivery(login.user(), actA, account);
    String jobB = jobs.delivery(login.user(), actB, account);
    String jobC = jobs.delivery(login.user(), actC, account);

    when(connector.uploadFit(any(), any(), any()))
        .thenReturn(
            ConnectorResult.processing("token-a"),
            ConnectorResult.processing("token-b"),
            ConnectorResult.processing("token-c"));
    jobs.runOne(jobA);
    jobs.runOne(jobB);
    jobs.runOne(jobC);
    assertEquals(
        "CONFIRMING", store.one("SELECT status FROM sync_job WHERE id=?", jobA).get("status"));

    // 模拟另一个有重试中的 PULL 任务，不应阻塞已进入 CONFIRMING 的确认检查。
    store.db.update(
        "INSERT INTO"
            + " sync_job(id,user_id,kind,status,account_id,activity_id,idempotency_key,payload_json,attempt_count,next_run_at,created_at,updated_at)"
            + " VALUES(?,?,'PULL','RETRY',?,NULL,?,?,1,?,?,?)",
        CloudStore.id(),
        login.user(),
        account,
        "pull-retry:" + CloudStore.id(),
        "{}",
        System.currentTimeMillis() + 60_000,
        System.currentTimeMillis(),
        System.currentTimeMillis());

    store.db.update("UPDATE sync_job SET next_run_at=0 WHERE id IN (?,?,?)", jobA, jobB, jobC);
    when(connector.checkUpload(any(), eq("token-a")))
        .thenReturn(ConnectorResult.processing("token-a-next"));
    when(connector.listActivities(any(), anyLong(), isNull()))
        .thenReturn(
            ConnectorResult.success(
                new ActivityPage(
                    List.of(
                        new RemoteActivity(
                            "onelap-c",
                            fitEpochOffsetMs + 300_000L * 1000L,
                            "Dubai 虚拟骑行",
                            21_000.0,
                            null,
                            Map.of())),
                    "2",
                    true)));
    when(connector.listActivities(any(), anyLong(), eq("2")))
        .thenReturn(
            ConnectorResult.success(
                new ActivityPage(
                    List.of(
                        new RemoteActivity(
                            "onelap-b",
                            fitEpochOffsetMs + 200_000L * 1000L,
                            "Dubai 虚拟骑行",
                            18_000.0,
                            null,
                            Map.of()),
                        new RemoteActivity(
                            "onelap-a",
                            fitEpochOffsetMs + 100_000L * 1000L,
                            "Mahdha 虚拟骑行",
                            32_500.0,
                            null,
                            Map.of())),
                    "3",
                    false)));

    jobs.runOne(jobA);

    assertEquals(
        "SUCCEEDED", store.one("SELECT status FROM sync_job WHERE id=?", jobA).get("status"));
    assertEquals(
        "SUCCEEDED", store.one("SELECT status FROM sync_job WHERE id=?", jobB).get("status"));
    assertEquals(
        "SUCCEEDED", store.one("SELECT status FROM sync_job WHERE id=?", jobC).get("status"));
    assertEquals(
        "onelap-a",
        store
            .one(
                "SELECT remote_id FROM platform_copy WHERE user_id=? AND activity_id=? AND"
                    + " account_id=?",
                login.user(),
                actA,
                account)
            .get("remote_id"));
    assertEquals(
        "onelap-b",
        store
            .one(
                "SELECT remote_id FROM platform_copy WHERE user_id=? AND activity_id=? AND"
                    + " account_id=?",
                login.user(),
                actB,
                account)
            .get("remote_id"));
    assertEquals(
        "onelap-c",
        store
            .one(
                "SELECT remote_id FROM platform_copy WHERE user_id=? AND activity_id=? AND"
                    + " account_id=?",
                login.user(),
                actC,
                account)
            .get("remote_id"));
    verify(connector, never()).downloadFit(any(), any());
  }

  @Test
  void confirmingDeliveryReconcilesReencodedFitViaDeduplicatorAutoMerge() throws Exception {
    Login login = login();
    String account = account(login);
    long fitEpochOffsetMs = 631_065_600_000L;
    byte[] uploaded = rideFit(400_000, 2, 3600_000, 3500_000, 2500_000, 145, 1);
    byte[] reencoded = rideFit(400_005, 2, 3600_000, 3500_000, 2500_000, 146, 2);
    String act =
        activities
            .importFit(login.user(), uploaded, "LOCAL", null, null, "Villanueva 虚拟骑行")
            .get("activityId")
            .toString();
    String job = jobs.delivery(login.user(), act, account);

    when(connector.uploadFit(any(), any(), any()))
        .thenReturn(ConnectorResult.processing("token-reencoded"));
    jobs.runOne(job);
    store.db.update("UPDATE sync_job SET next_run_at=0 WHERE id=?", job);

    RemoteActivity remoteWithoutSummary =
        new RemoteActivity(
            "onelap-reencoded",
            fitEpochOffsetMs + 400_000L * 1000L,
            "未命名",
            null,
            "https://files.onelap.cn/reencoded.fit",
            Map.of());
    when(connector.checkUpload(any(), eq("token-reencoded")))
        .thenReturn(ConnectorResult.processing("token-reencoded-2"));
    when(connector.listActivities(any(), anyLong(), isNull()))
        .thenReturn(
            ConnectorResult.success(new ActivityPage(List.of(remoteWithoutSummary), "2", false)));
    when(connector.downloadFit(any(), eq(remoteWithoutSummary)))
        .thenReturn(ConnectorResult.success(new FitDownload(reencoded, "reencoded.fit")));

    jobs.runOne(job);

    assertEquals(
        "SUCCEEDED", store.one("SELECT status FROM sync_job WHERE id=?", job).get("status"));
    assertEquals(
        "onelap-reencoded",
        store
            .one(
                "SELECT remote_id FROM platform_copy WHERE user_id=? AND activity_id=? AND"
                    + " account_id=?",
                login.user(),
                act,
                account)
            .get("remote_id"));
  }

  private static int crc(byte[] bytes, int count) {
    int crc = 0;
    for (int i = 0; i < count; i++) {
      crc ^= bytes[i] & 255;
      for (int b = 0; b < 8; b++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
    }
    return crc;
  }
}
