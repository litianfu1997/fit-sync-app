package com.fitsync.cloud;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fitsync.cloud.connector.*;
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
      "fitsync.auth-rate-limit=1000"
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

  private static int crc(byte[] bytes, int count) {
    int crc = 0;
    for (int i = 0; i < count; i++) {
      crc ^= bytes[i] & 255;
      for (int b = 0; b < 8; b++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
    }
    return crc;
  }
}
