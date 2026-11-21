package com.fitsync.cloud;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:authtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "fitsync.storage-dir=./target/auth-test-fits",
      "fitsync.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "fitsync.scheduler-enabled=false",
      "fitsync.auth-rate-limit=1000",
      "spring.mail.host=smtp.test.local",
      "spring.mail.username=noreply@test.local",
      "spring.mail.password=test-secret",
      "fitsync.mail.from=noreply@test.local",
      "fitsync.google.client-id=google-client-1",
      "fitsync.google.client-secret=google-secret-1",
      "fitsync.google.redirect-uri="
    })
@ActiveProfiles("local")
@AutoConfigureMockMvc
class AuthIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired CloudStore store;
  @Autowired PasswordService passwords;
  @Autowired GoogleOAuthService googleOAuth;
  @MockitoBean JavaMailSender mailSender;

  private final AtomicReference<String> lastMailText = new AtomicReference<>("");

  @BeforeEach
  void stubMailSender() {
    reset(mailSender);
    lastMailText.set("");
    doAnswer(
            inv -> {
              SimpleMailMessage msg = inv.getArgument(0);
              lastMailText.set(msg.getText());
              return null;
            })
        .when(mailSender)
        .send(any(SimpleMailMessage.class));
  }

  @AfterEach
  void resetGoogleResolver() {
    googleOAuth.setIdentityResolverForTest(null);
  }

  private String extractCodeFromLastMail() {
    Matcher m = Pattern.compile("(\\d{6})").matcher(lastMailText.get());
    assertTrue(m.find(), "应收到包含 6 位验证码的邮件: " + lastMailText.get());
    return m.group(1);
  }

  private static String queryParam(String url, String key) {
    String query = URI.create(url).getRawQuery();
    if (query == null) return "";
    for (String pair : query.split("&")) {
      int idx = pair.indexOf('=');
      if (idx > 0
          && URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8).equals(key)) {
        return URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
      }
    }
    return "";
  }

  private JsonNode perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
      throws Exception {
    return store.json.readTree(
        mvc.perform(req)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode register(String username, String password, String name) throws Exception {
    return perform(
        post("/api/v1/auth/register")
            .contentType("application/json")
            .content(
                store.encode(
                    Map.of("username", username, "password", password, "displayName", name))));
  }

  private JsonNode login(String username, String password) throws Exception {
    return perform(
        post("/api/v1/auth/login")
            .contentType("application/json")
            .content(store.encode(Map.of("username", username, "password", password))));
  }

  @Test
  void configAdvertisesPasswordEmailAndGoogleLogin() throws Exception {
    mvc.perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passwordLoginEnabled").value(true))
        .andExpect(jsonPath("$.emailAuthEnabled").value(true))
        .andExpect(jsonPath("$.googleLoginEnabled").value(true))
        .andExpect(jsonPath("$.admob.enabled").value(false))
        .andExpect(jsonPath("$.admob.bannerAdUnitId").value(""))
        .andExpect(jsonPath("$.webLoginEnabled").doesNotExist());
  }

  @Test
  void emailVerificationCodeCooldownLockoutRegisterAndLogin() throws Exception {
    String email = "Rider.Mail@Example.COM";
    mvc.perform(
            post("/api/v1/auth/email/code")
                .contentType("application/json")
                .content(store.encode(Map.of("email", email, "purpose", "REGISTER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
    String firstCode = extractCodeFromLastMail();

    // 60 秒冷却期内不允许重复发送。
    mvc.perform(
            post("/api/v1/auth/email/code")
                .contentType("application/json")
                .content(store.encode(Map.of("email", email, "purpose", "REGISTER"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("验证码发送过于频繁，请稍后再试"));

    // 连续 5 次输入错误验证码后，当前验证码作废。
    String wrongCode = "000000".equals(firstCode) ? "999999" : "000000";
    for (int i = 0; i < 5; i++) {
      mvc.perform(
              post("/api/v1/auth/register")
                  .contentType("application/json")
                  .content(
                      store.encode(
                          Map.of(
                              "email", email,
                              "code", wrongCode,
                              "password", "mail-pass-123",
                              "displayName", "邮箱骑友"))))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of(
                            "email", email,
                            "code", firstCode,
                            "password", "mail-pass-123",
                            "displayName", "邮箱骑友"))))
        .andExpect(status().isBadRequest());

    // 重新发码并成功注册（把数据库记录的 created_at 推前以跳过 60s 冷却等待）。
    store.db.update(
        "UPDATE email_verification_code SET created_at=0 WHERE email=?", "rider.mail@example.com");
    mvc.perform(
            post("/api/v1/auth/email/code")
                .contentType("application/json")
                .content(store.encode(Map.of("email", email, "purpose", "REGISTER"))))
        .andExpect(status().isOk());
    String secondCode = extractCodeFromLastMail();

    JsonNode created =
        perform(
            post("/api/v1/auth/register")
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of(
                            "email", email,
                            "code", secondCode,
                            "password", "mail-pass-123",
                            "displayName", "邮箱骑友"))));
    assertEquals("rider.mail@example.com", created.get("user").get("email").asText());
    assertTrue(created.get("user").get("hasEmail").asBoolean());
    assertTrue(created.get("user").get("hasPassword").asBoolean());
    assertFalse(created.get("user").get("hasGoogle").asBoolean());

    // 邮箱不区分大小写登录。
    JsonNode loggedIn =
        perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(store.encode(Map.of("account", "RIDER.MAIL@example.com", "password", "mail-pass-123"))));
    assertEquals(created.get("user").get("id").asText(), loggedIn.get("user").get("id").asText());

    // 已注册邮箱不可重复发送注册验证码。
    mvc.perform(
            post("/api/v1/auth/email/code")
                .contentType("application/json")
                .content(store.encode(Map.of("email", "rider.mail@example.com", "purpose", "REGISTER"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("该邮箱已被注册，请直接登录"));
  }

  @Test
  void emailCodeLoginWorksForRegisteredEmailWithoutPassword() throws Exception {
    // 未注册邮箱不可请求登录验证码。
    mvc.perform(
            post("/api/v1/auth/email/code")
                .contentType("application/json")
                .content(store.encode(Map.of("email", "nobody@example.com", "purpose", "LOGIN"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("该邮箱尚未注册，请先注册"));

    String id = CloudStore.id();
    store.db.update(
        "INSERT INTO app_user(id,email,display_name,created_at) VALUES(?,?,?,?)",
        id,
        "code.login@example.com",
        "验证码用户",
        System.currentTimeMillis());

    mvc.perform(
            post("/api/v1/auth/email/code")
                .contentType("application/json")
                .content(store.encode(Map.of("email", "Code.Login@example.com", "purpose", "LOGIN"))))
        .andExpect(status().isOk());
    String code = extractCodeFromLastMail();
    String wrong = "000000".equals(code) ? "999999" : "000000";

    mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(store.encode(Map.of("email", "code.login@example.com", "code", wrong))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("邮箱验证码不正确"));

    JsonNode result =
        perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(store.encode(Map.of("email", "CODE.LOGIN@example.com", "code", code))));
    assertEquals(id, result.get("user").get("id").asText());
    assertFalse(result.get("token").asText().isBlank());

    // 验证码一次性，不可重放。
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(store.encode(Map.of("email", "code.login@example.com", "code", code))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void usernameUserCanBindEmailAndLoginWithEither() throws Exception {
    JsonNode created = register("bind_email_user", "user-pass-123", "绑邮测试");
    String token = created.get("token").asText();
    String bindEmail = "bind.me@example.com";

    // 未登录不得发送绑定验证码。
    mvc.perform(
            post("/api/v1/auth/email/code")
                .contentType("application/json")
                .content(store.encode(Map.of("email", bindEmail, "purpose", "BIND"))))
        .andExpect(status().isUnauthorized());

    mvc.perform(
            post("/api/v1/auth/email/code")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(store.encode(Map.of("email", bindEmail, "purpose", "BIND"))))
        .andExpect(status().isOk());
    String code = extractCodeFromLastMail();

    mvc.perform(
            post("/api/v1/auth/email/bind")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(store.encode(Map.of("email", bindEmail, "code", code))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(bindEmail))
        .andExpect(jsonPath("$.hasEmail").value(true))
        .andExpect(jsonPath("$.username").value("bind_email_user"));

    assertEquals(
        created.get("user").get("id").asText(),
        login("bind_email_user", "user-pass-123").get("user").get("id").asText());
    JsonNode viaEmail =
        perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(store.encode(Map.of("account", "BIND.ME@EXAMPLE.COM", "password", "user-pass-123"))));
    assertEquals(created.get("user").get("id").asText(), viaEmail.get("user").get("id").asText());
  }

  @Test
  void googleOAuthLoginMergesExistingEmailAndExchangesOneTimeTicket() throws Exception {
    // 先用邮箱注册一个已有用户。
    mvc.perform(
            post("/api/v1/auth/email/code")
                .contentType("application/json")
                .content(store.encode(Map.of("email", "merge-rider@example.com", "purpose", "REGISTER"))))
        .andExpect(status().isOk());
    String code = extractCodeFromLastMail();
    JsonNode existing =
        perform(
            post("/api/v1/auth/register")
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of(
                            "email", "merge-rider@example.com",
                            "code", code,
                            "password", "merge-pass-1",
                            "displayName", "原邮箱账号"))));
    String existingId = existing.get("user").get("id").asText();

    // 发起 Google 登录并验证安全参数。
    JsonNode startJson =
        perform(
            post("/api/v1/auth/google/start")
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of(
                            "redirectUri", "http://127.0.0.1:8080/api/v1/auth/google/callback",
                            "returnUrl", "http://127.0.0.1:5273/#/pages/overview/index"))));
    String authorizeUrl = startJson.get("authorizeUrl").asText();
    assertTrue(authorizeUrl.startsWith("https://accounts.google.com/o/oauth2/v2/auth"));
    String state = queryParam(authorizeUrl, "state");
    assertFalse(state.isBlank());

    googleOAuth.setIdentityResolverForTest(
        (cid, csec, redir, authCode) ->
            new GoogleOAuthService.GoogleProfile(
                "google-sub-merge-1", "MERGE-RIDER@example.com", true, "Google Name"));

    var callbackResult =
        mvc.perform(
                get("/api/v1/auth/google/callback")
                    .param("state", state)
                    .param("code", "google-code-1"))
            .andExpect(status().isFound())
            .andReturn();
    String location = callbackResult.getResponse().getHeader("Location");
    assertNotNull(location);
    assertTrue(location.startsWith("http://127.0.0.1:5273/#/pages/overview/index?googleTicket="));
    String ticket = location.substring(location.indexOf("googleTicket=") + "googleTicket=".length());

    // 同一 state 不可重放。
    mvc.perform(
            get("/api/v1/auth/google/callback")
                .param("state", state)
                .param("code", "google-code-1"))
        .andExpect(status().isBadRequest());

    // 使用一次性 ticket 换取正式会话，应自动合并到同邮箱用户。
    JsonNode exchanged =
        perform(
            post("/api/v1/auth/google/exchange")
                .contentType("application/json")
                .content(store.encode(Map.of("ticket", ticket))));
    assertEquals(existingId, exchanged.get("user").get("id").asText());
    assertTrue(exchanged.get("user").get("hasGoogle").asBoolean());
    assertTrue(exchanged.get("user").get("hasEmail").asBoolean());

    // 同一 ticket 不可二次使用。
    mvc.perform(
            post("/api/v1/auth/google/exchange")
                .contentType("application/json")
                .content(store.encode(Map.of("ticket", ticket))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void googleCreatedUserCanSetPasswordWithoutUsernameAndExistingUserCanBindGoogle()
      throws Exception {
    googleOAuth.setIdentityResolverForTest(
        (cid, csec, redir, authCode) ->
            new GoogleOAuthService.GoogleProfile(
                "google-sub-fresh-1", "fresh.google@example.com", true, "Fresh Google"));

    JsonNode startFresh =
        perform(
            post("/api/v1/auth/google/start")
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of(
                            "redirectUri", "http://127.0.0.1:8080/api/v1/auth/google/callback",
                            "returnUrl", "http://127.0.0.1:5273/#/pages/overview/index"))));
    String freshState = queryParam(startFresh.get("authorizeUrl").asText(), "state");
    String freshLocation =
        mvc.perform(
                get("/api/v1/auth/google/callback")
                    .param("state", freshState)
                    .param("code", "fresh-code"))
            .andExpect(status().isFound())
            .andReturn()
            .getResponse()
            .getHeader("Location");
    String freshTicket =
        freshLocation.substring(
            freshLocation.indexOf("googleTicket=") + "googleTicket=".length());
    JsonNode freshSession =
        perform(
            post("/api/v1/auth/google/exchange")
                .contentType("application/json")
                .content(store.encode(Map.of("ticket", freshTicket))));
    String freshToken = freshSession.get("token").asText();
    assertFalse(freshSession.get("user").get("hasPassword").asBoolean());
    assertTrue(freshSession.get("user").get("hasEmail").asBoolean());
    assertTrue(freshSession.get("user").get("hasGoogle").asBoolean());

    // Google 新用户在已有邮箱时无需填用户名即可设置登录密码。
    mvc.perform(
            post("/api/v1/auth/password")
                .header("Authorization", "Bearer " + freshToken)
                .contentType("application/json")
                .content(store.encode(Map.of("password", "google-user-pass-1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasPassword").value(true))
        .andExpect(jsonPath("$.email").value("fresh.google@example.com"));

    JsonNode emailLogin =
        perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of(
                            "account", "fresh.google@example.com",
                            "password", "google-user-pass-1"))));
    assertEquals(
        freshSession.get("user").get("id").asText(), emailLogin.get("user").get("id").asText());

    // 既有用户名账号在设置页绑定 Google 账号。
    JsonNode normal = register("bind_google_user", "normal-pass-1", "绑定谷歌");
    String normalToken = normal.get("token").asText();

    // 未登录发起绑定应被拦截。
    mvc.perform(post("/api/v1/auth/google/bind/start"))
        .andExpect(status().isUnauthorized());

    JsonNode bindStart =
        perform(
            post("/api/v1/auth/google/bind/start")
                .header("Authorization", "Bearer " + normalToken)
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of(
                            "redirectUri", "http://127.0.0.1:8080/api/v1/auth/google/callback",
                            "returnUrl", "http://127.0.0.1:5273/#/pages/settings/index"))));
    String bindState = queryParam(bindStart.get("authorizeUrl").asText(), "state");

    googleOAuth.setIdentityResolverForTest(
        (cid, csec, redir, authCode) ->
            new GoogleOAuthService.GoogleProfile(
                "google-sub-bound-2", "bound.google@example.com", true, "Bound Google"));
    mvc.perform(
            get("/api/v1/auth/google/callback")
                .param("state", bindState)
                .param("code", "bind-code"))
        .andExpect(status().isFound())
        .andExpect(
            header()
                .string(
                    "Location",
                    "http://127.0.0.1:5273/#/pages/settings/index?googleOAuth=success"));

    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + normalToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasGoogle").value(true))
        .andExpect(jsonPath("$.email").value("bound.google@example.com"));
  }

  @Test
  void registerIssuesSessionAndLoginIsCaseInsensitiveAndPasswordIsChecked() throws Exception {
    JsonNode created = register("Rider_One", "correct-horse-battery", "骑友甲");
    String token = created.get("token").asText();
    assertEquals("rider_one", created.get("user").get("username").asText());
    assertTrue(created.get("user").get("hasPassword").asBoolean());
    assertFalse(created.get("user").get("hasWechat").asBoolean());
    assertEquals("骑友甲", created.get("user").get("displayName").asText());

    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("rider_one"))
        .andExpect(jsonPath("$.hasPassword").value(true));

    JsonNode reloaded = login("RIDER_ONE", "correct-horse-battery");
    assertNotEquals(token, reloaded.get("token").asText());
    assertEquals(created.get("user").get("id").asText(), reloaded.get("user").get("id").asText());

    mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(store.encode(Map.of("username", "Rider_One", "password", "wrong-password"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("账号或密码错误"));

    String storedHash =
        store.db.queryForObject(
            "SELECT password_hash FROM app_user WHERE username=?", String.class, "rider_one");
    assertFalse(storedHash.contains("correct-horse-battery"));
    assertTrue(passwords.matches("correct-horse-battery", storedHash));
    assertFalse(passwords.matches("wrong-password", storedHash));
  }

  @Test
  void duplicateUsernameIsRejected() throws Exception {
    register("rider_dup", "password-123", "甲");
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of(
                            "username", "RIDER_DUP", "password", "password-456", "displayName", "乙"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("该用户名已被注册"));
  }

  @Test
  void invalidUsernameOrShortPasswordAreRejected() throws Exception {
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType("application/json")
                .content(store.encode(Map.of("username", "ab", "password", "password-123"))))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType("application/json")
                .content(store.encode(Map.of("username", "valid_name", "password", "short"))))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType("application/json")
                .content(store.encode(Map.of("username", "bad name", "password", "password-123"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void wechatCreatedUserCanSetPasswordAndThenLoginFromWeb() throws Exception {
    // 模拟只有微信身份、没有密码的既有用户（与 V1 迁移后的用户结构一致）。
    String id = CloudStore.id();
    store.db.update(
        "INSERT INTO app_user(id,wechat_openid,display_name,created_at) VALUES(?,?,?,?)",
        id,
        "openid-" + id,
        "老用户",
        System.currentTimeMillis());
    // dev-login 无法指定用户，这里直接为既有微信用户插入一个测试会话。
    String token = "test-known-token-" + id;
    store.db.update(
        "INSERT INTO user_session(token_hash,user_id,expires_at) VALUES(?,?,?)",
        CryptoService.hash(token),
        id,
        System.currentTimeMillis() + 3_600_000L);

    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasPassword").value(false))
        .andExpect(jsonPath("$.hasWechat").value(true));

    mvc.perform(
            post("/api/v1/auth/password")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(store.encode(Map.of("username", "old_rider", "password", "new-password-1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasPassword").value(true))
        .andExpect(jsonPath("$.username").value("old_rider"));

    JsonNode webLogin = login("OLD_RIDER", "new-password-1");
    assertEquals(id, webLogin.get("user").get("id").asText());

    mvc.perform(
            post("/api/v1/auth/password")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(store.encode(Map.of("username", "another", "password", "new-password-2"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void changePasswordRequiresOldPasswordAndProfileUpdatePersists() throws Exception {
    JsonNode created = register("rider_change", "old-password-1", "原名");
    String token = created.get("token").asText();

    mvc.perform(
            post("/api/v1/auth/password/change")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of("oldPassword", "not-the-old", "newPassword", "new-password-2"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("原密码不正确"));

    mvc.perform(
            put("/api/v1/me")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(store.encode(Map.of("displayName", "新昵称"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("新昵称"));

    mvc.perform(
            post("/api/v1/auth/password/change")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of("oldPassword", "old-password-1", "newPassword", "new-password-2"))))
        .andExpect(status().isOk());

    mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(store.encode(Map.of("username", "rider_change", "password", "old-password-1"))))
        .andExpect(status().isBadRequest());
    assertEquals(
        created.get("user").get("id").asText(),
        login("rider_change", "new-password-2").get("user").get("id").asText());
  }

  @Test
  void privateAuthEndpointsRequireSession() throws Exception {
    mvc.perform(
            post("/api/v1/auth/password")
                .contentType("application/json")
                .content(store.encode(Map.of("username", "nobody", "password", "password-123"))))
        .andExpect(status().isUnauthorized());
    mvc.perform(put("/api/v1/me").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/wechat/bind")
                .contentType("application/json")
                .content(store.encode(Map.of("code", "wx-code"))))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/password/change")
                .contentType("application/json")
                .content(store.encode(Map.of("oldPassword", "a", "newPassword", "b"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void wechatBindingRequiresServerConfiguration() throws Exception {
    JsonNode created = register("rider_bind", "password-123", "甲");
    String token = created.get("token").asText();
    // local 测试配置没有微信 AppID/AppSecret，绑定接口应明确拒绝，而不是发出外部请求。
    mvc.perform(
            post("/api/v1/auth/wechat/bind")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(store.encode(Map.of("code", "wx-code"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("微信登录尚未配置"));
    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasWechat").value(false));
  }

  @Test
  void googleOAuthAllowsCloudAppSchemeReturnUrlAndRejectsUntrustedSchemes() throws Exception {
    var allowedResp =
        mvc.perform(
                post("/api/v1/auth/google/start")
                    .contentType("application/json")
                    .content(
                        store.encode(
                            Map.of(
                                "redirectUri",
                                "https://fit.example.com/api/v1/auth/google/callback",
                                "returnUrl",
                                "fitsync-cloud://oauth?returnPath=%2Fpages%2Foverview%2Findex"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    String authorizeUrl =
        store.json.readTree(allowedResp.getContentAsString()).get("authorizeUrl").asText();
    String state = queryParam(authorizeUrl, "state");

    googleOAuth.setIdentityResolverForTest(
        (cid, csecret, ruri, code) ->
            new GoogleOAuthService.GoogleProfile(
                "google-sub-app-1", "app.rider@example.com", true, "云端骑友"));
    var callbackResp =
        mvc.perform(
                get("/api/v1/auth/google/callback")
                    .param("state", state)
                    .param("code", "app-code"))
            .andExpect(status().isFound())
            .andReturn()
            .getResponse();
    String location = callbackResp.getHeader("Location");
    assertNotNull(location);
    assertTrue(location.startsWith("fitsync-cloud://oauth?returnPath=%2Fpages%2Foverview%2Findex"));
    assertTrue(location.contains("googleTicket="));

    mvc.perform(
            post("/api/v1/auth/google/start")
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of(
                            "redirectUri",
                            "https://fit.example.com/api/v1/auth/google/callback",
                            "returnUrl",
                            "fitsync-cloud://other-host"))))
        .andExpect(status().isBadRequest());

    mvc.perform(
            post("/api/v1/auth/google/start")
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of(
                            "redirectUri",
                            "https://fit.example.com/api/v1/auth/google/callback",
                            "returnUrl",
                            "javascript://alert(1)"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void deleteAccountRemovesUserSessionsRecordsAndStoredFits() throws Exception {
    mvc.perform(delete("/api/v1/me")).andExpect(status().isUnauthorized());

    JsonNode created = register("rider_delete_me", "delete-pass-123", "待注销用户");
    String token = created.get("token").asText();
    String userId = created.get("user").get("id").asText();

    // 为该用户写入平台账号、活动、原件、规则、任务以及磁盘 FIT 文件，确认注销能完整清理。
    String accountId = CloudStore.id();
    String activityId = CloudStore.id();
    String variantId = CloudStore.id();
    long now = System.currentTimeMillis();
    java.nio.file.Path userDir = java.nio.file.Path.of("./target/auth-test-fits", userId);
    java.nio.file.Files.createDirectories(userDir);
    java.nio.file.Path fitFile = userDir.resolve("sample.fit");
    java.nio.file.Files.writeString(fitFile, "dummy-fit");

    store.db.update(
        "INSERT INTO platform_account(id,user_id,platform,display_name,status,encrypted_session)"
            + " VALUES(?,?,?,?,?,?)",
        accountId,
        userId,
        "COROS",
        "COROS",
        "ACTIVE",
        "enc");
    store.db.update(
        "INSERT INTO activity(id,user_id,title,start_time,source_platform,needs_review,created_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        activityId,
        userId,
        "测试骑行",
        now,
        "LOCAL",
        false,
        now);
    store.db.update(
        "INSERT INTO"
            + " activity_variant(id,user_id,activity_id,sha256,file_path,metadata_json,quality_score)"
            + " VALUES(?,?,?,?,?,?,?)",
        variantId,
        userId,
        activityId,
        "abc123",
        userId + "/sample.fit",
        "{}",
        10);

    mvc.perform(delete("/api/v1/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    assertEquals(
        0,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM app_user WHERE id=?", Integer.class, userId));
    assertEquals(
        0,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM user_session WHERE user_id=?", Integer.class, userId));
    assertEquals(
        0,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM activity WHERE user_id=?", Integer.class, userId));
    assertEquals(
        0,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM platform_account WHERE user_id=?", Integer.class, userId));
    assertFalse(java.nio.file.Files.exists(fitFile));
    assertFalse(java.nio.file.Files.exists(userDir));

    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }
}
