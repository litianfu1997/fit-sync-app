package com.fitsync.cloud;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 校验默认 CORS 白名单与 uni-app H5 开发端口一致。
 *
 * <p>H5 开发时页面跑在 Vite（默认 5273），浏览器直连代理后的 /api 会带上页面自身 Origin； 该来源不在白名单时 CorsFilter 会在进入 Controller 前直接返回 403
 * "Invalid CORS request"， 表现为注册/登录"请求失败"而 GET /api/v1/config 也不可用。
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:corstest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "fitsync.storage-dir=./target/cors-test-fits",
      "fitsync.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "fitsync.scheduler-enabled=false",
      "fitsync.auth-rate-limit=1000"
    })
@ActiveProfiles("local")
@AutoConfigureMockMvc
class CorsConfigurationTest {
  @Autowired MockMvc mvc;
  @Autowired CloudStore store;

  @Test
  void h5DevOriginOnConfiguredPortCanRegister() throws Exception {
    mvc.perform(
            post("/api/v1/auth/register")
                .header("Origin", "http://127.0.0.1:5273")
                .contentType("application/json")
                .content(
                    store.encode(
                        Map.of(
                            "username", "cors_rider",
                            "password", "password-123",
                            "displayName", "跨域骑友"))))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:5273"))
        .andExpect(jsonPath("$.token").isNotEmpty());
  }

  @Test
  void localhostDevOriginIsAllowedToo() throws Exception {
    mvc.perform(get("/api/v1/config").header("Origin", "http://localhost:5273"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5273"));
  }

  @Test
  void unlistedOriginIsStillRejected() throws Exception {
    // 白名单不是通配：未配置的来源必须继续被拒绝，避免放开任意网页跨域调用。
    mvc.perform(get("/api/v1/config").header("Origin", "http://evil.example.com"))
        .andExpect(status().isForbidden());
  }

  @Test
  void extensionOriginRequiresExplicitAllowListEntry() throws Exception {
    // 会话助手扩展以 chrome-extension://<id> 作为 Origin；只能是显式白名单，
    // 不能因为“是扩展”就无条件放行，否则任意扩展都能调用用户 API。
    mvc.perform(
            get("/api/v1/config")
                .header("Origin", "chrome-extension://abcdefghijklmnopabcdefghijklmnop"))
        .andExpect(status().isForbidden());
  }
}
