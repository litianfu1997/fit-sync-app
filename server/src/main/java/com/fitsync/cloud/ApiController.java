package com.fitsync.cloud;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
  private final AuthService auth;
  private final DashboardService dashboards;
  private final ActivityService activities;
  private final AccountService accounts;
  private final JobService jobs;
  private final String nativeAd;

  public ApiController(
      AuthService auth,
      DashboardService dashboards,
      ActivityService activities,
      AccountService accounts,
      JobService jobs,
      @Value("${fitsync.native-ad-unit-id:}") String nativeAd) {
    this.auth = auth;
    this.dashboards = dashboards;
    this.activities = activities;
    this.accounts = accounts;
    this.jobs = jobs;
    this.nativeAd = nativeAd;
  }

  private String user(HttpServletRequest request) {
    return request.getAttribute("userId").toString();
  }

  @GetMapping("/config")
  public Map<String, Object> config() {
    var platforms =
        List.of(
            platform("COROS", "高驰 COROS", true),
            platform("IGPSPORT", "iGPSPORT", true),
            platform("ONELAP", "迈金", false),
            platform("XINGZHE", "行者", false),
            platform("GARMIN", "Garmin", false),
            platform("STRAVA", "Strava", false));
    return Map.of(
        "wechatLoginEnabled",
        auth.wechatEnabled(),
        "webLoginEnabled",
        auth.wechatEnabled(),
        "passwordLoginEnabled",
        auth.passwordLoginEnabled(),
        "devLoginEnabled",
        auth.devEnabled(),
        "ads",
        Map.of(
            "enabled",
            !nativeAd.isBlank(),
            "nativeAdUnitId",
            nativeAd,
            "rewardedAdUnitId",
            "",
            "rewardVerificationEnabled",
            false),
        "platforms",
        platforms);
  }

  private static Map<String, Object> platform(String code, String name, boolean supported) {
    return Map.of(
        "code",
        code,
        "name",
        name,
        "authMode",
        supported ? "PASSWORD" : "UNAVAILABLE",
        "experimental",
        true);
  }

  public record DevLogin(String displayName) {}

  public record WechatLogin(String code) {}

  @PostMapping("/auth/dev-login")
  public Map<String, Object> devLogin(@RequestBody(required = false) DevLogin body) {
    return auth.devLogin(body == null ? null : body.displayName());
  }

  @PostMapping("/auth/wechat")
  public Map<String, Object> wechat(@RequestBody WechatLogin body) {
    return auth.wechat(body.code());
  }

  public record Register(String username, String password, String displayName) {
    @Override
    public String toString() {
      return "Register[凭据已隐藏]";
    }
  }

  public record PasswordLogin(String username, String password) {
    @Override
    public String toString() {
      return "PasswordLogin[凭据已隐藏]";
    }
  }

  public record SetPassword(String username, String password) {
    @Override
    public String toString() {
      return "SetPassword[凭据已隐藏]";
    }
  }

  public record ChangePassword(String oldPassword, String newPassword) {
    @Override
    public String toString() {
      return "ChangePassword[凭据已隐藏]";
    }
  }

  public record Profile(String displayName) {}

  @PostMapping("/auth/register")
  public Map<String, Object> register(@RequestBody Register body) {
    return auth.register(body.username(), body.password(), body.displayName());
  }

  @PostMapping("/auth/login")
  public Map<String, Object> passwordLogin(@RequestBody PasswordLogin body) {
    return auth.passwordLogin(body.username(), body.password());
  }

  @PostMapping("/auth/wechat/bind")
  public Map<String, Object> bindWechat(
      HttpServletRequest request, @RequestBody WechatLogin body) {
    return auth.bindWechat(user(request), body.code());
  }

  @PostMapping("/auth/password")
  public Map<String, Object> setPassword(
      HttpServletRequest request, @RequestBody SetPassword body) {
    return auth.setPassword(user(request), body.username(), body.password());
  }

  @PostMapping("/auth/password/change")
  public Map<String, Object> changePassword(
      HttpServletRequest request, @RequestBody ChangePassword body) {
    return auth.changePassword(user(request), body.oldPassword(), body.newPassword());
  }

  @PostMapping("/auth/web-challenges")
  public Map<String, Object> webChallenge() {
    return auth.createWebChallenge();
  }

  @GetMapping("/auth/web-challenges/{id}")
  public Map<String, Object> pollChallenge(
      @PathVariable String id,
      @RequestHeader(value = "X-Login-Poll-Token", required = false) String poll) {
    return auth.pollWebChallenge(id, poll);
  }

  public record ConfirmWeb(String code, boolean confirm) {}

  @PostMapping("/auth/web-challenges/confirm")
  public Map<String, Object> confirmChallenge(
      HttpServletRequest request, @RequestBody ConfirmWeb body) {
    return auth.confirmWebChallenge(user(request), body.code(), body.confirm());
  }

  @GetMapping("/me")
  public Map<String, Object> me(HttpServletRequest request) {
    return auth.account(user(request));
  }

  @PutMapping("/me")
  public Map<String, Object> updateProfile(
      HttpServletRequest request, @RequestBody Profile body) {
    return auth.updateProfile(user(request), body.displayName());
  }

  @PostMapping("/auth/logout")
  public Map<String, Object> logout(HttpServletRequest request) {
    auth.logout(user(request), request.getHeader("Authorization"));
    return Map.of("success", true);
  }

  @GetMapping("/dashboard")
  public Map<String, Object> dashboard(HttpServletRequest request) {
    return dashboards.summary(user(request));
  }

  @GetMapping("/activities")
  public Map<String, Object> activities(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    validatePage(page);
    return activities.page(user(request), page, size);
  }

  @PostMapping(value = "/activities/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> importFit(
      HttpServletRequest request, @RequestPart("file") MultipartFile file) throws Exception {
    var result = activities.importFit(user(request), file.getBytes(), "LOCAL", null, null);
    jobs.plan(user(request), result.get("activityId").toString());
    return result;
  }

  @GetMapping("/activities/{id}")
  public Map<String, Object> activity(HttpServletRequest request, @PathVariable String id) {
    return activities.detail(user(request), id);
  }

  @GetMapping("/activities/{id}/file")
  public ResponseEntity<byte[]> file(HttpServletRequest request, @PathVariable String id) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            "Content-Disposition",
            "attachment; filename=\"FitSync-" + UUID.fromString(id) + ".fit\"")
        .body(activities.file(user(request), id));
  }

  public record ActivityReview(String decision) {}

  @PostMapping("/activities/{id}/review")
  public Map<String, Object> review(
      HttpServletRequest request, @PathVariable String id, @RequestBody ActivityReview body) {
    var result = activities.keepSeparate(user(request), id, body.decision());
    jobs.plan(user(request), id);
    return result;
  }

  @GetMapping("/accounts")
  public List<Map<String, Object>> accounts(HttpServletRequest request) {
    return accounts.list(user(request));
  }

  public record Connect(
      String platform, String username, String password, boolean storeCredentials) {
    @Override
    public String toString() {
      return "Connect[凭据已隐藏]";
    }
  }

  @PostMapping("/accounts")
  public Map<String, Object> account(HttpServletRequest request, @RequestBody Connect body) {
    return accounts.connect(
        user(request), body.platform(), body.username(), body.password(), body.storeCredentials());
  }

  @DeleteMapping("/accounts/{id}")
  public Map<String, Object> deleteAccount(HttpServletRequest request, @PathVariable String id) {
    accounts.delete(user(request), id);
    return Map.of("success", true);
  }

  @GetMapping("/rules")
  public List<Map<String, Object>> rules(HttpServletRequest request) {
    return jobs.rules(user(request));
  }

  public record Rule(
      String sourcePlatform,
      String destinationAccountId,
      boolean enabled,
      boolean includeHistory) {}

  @PostMapping("/rules")
  public Map<String, Object> rule(HttpServletRequest request, @RequestBody Rule body) {
    if (body.sourcePlatform() == null) throw ApiException.bad("请选择来源平台");
    return jobs.rule(
        user(request),
        body.sourcePlatform().toUpperCase(Locale.ROOT),
        body.destinationAccountId(),
        body.enabled(),
        body.includeHistory());
  }

  @DeleteMapping("/rules/{id}")
  public Map<String, Object> deleteRule(HttpServletRequest request, @PathVariable String id) {
    jobs.deleteRule(user(request), id);
    return Map.of("success", true);
  }

  public record Sync(
      String sourcePlatform,
      List<String> destinationAccountIds,
      List<String> activityIds,
      Integer historyDays) {}

  @PostMapping("/sync")
  public Map<String, Object> sync(HttpServletRequest request, @RequestBody Sync body) {
    return jobs.sync(
        user(request),
        body.sourcePlatform(),
        body.destinationAccountIds(),
        body.activityIds(),
        body.historyDays() == null ? 90 : body.historyDays());
  }

  @GetMapping("/jobs")
  public Map<String, Object> jobs(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "30") int size) {
    validatePage(page);
    return jobs.page(user(request), page, size);
  }

  @PostMapping("/jobs/{id}/retry")
  public Map<String, Object> retry(HttpServletRequest request, @PathVariable String id) {
    jobs.retry(user(request), id);
    return Map.of("success", true);
  }

  private static void validatePage(int page) {
    if (page < 0 || page > 100000) throw ApiException.bad("页码无效");
  }
}
