package com.fitsync.cloud;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:authtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "fitsync.storage-dir=./target/auth-test-fits",
      "fitsync.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "fitsync.scheduler-enabled=false",
      "fitsync.auth-rate-limit=1000"
    })
@ActiveProfiles("local")
@AutoConfigureMockMvc
class AuthIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired CloudStore store;
  @Autowired PasswordService passwords;

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
  void configAdvertisesPasswordLogin() throws Exception {
    mvc.perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passwordLoginEnabled").value(true));
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
}
