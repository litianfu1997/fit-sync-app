package com.fitsync.cloud;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
  private final AuthService auth;
  private final DashboardService dashboards;
  private final ActivityService activities;
  private final AccountService accounts;
  private final StravaOAuthService stravaOAuth;
  private final GoogleOAuthService googleOAuth;
  private final JobService jobs;
  private final String nativeAd;

  public ApiController(
      AuthService auth,
      DashboardService dashboards,
      ActivityService activities,
      AccountService accounts,
      StravaOAuthService stravaOAuth,
      GoogleOAuthService googleOAuth,
      JobService jobs,
      @Value("${fitsync.native-ad-unit-id:}") String nativeAd) {
    this.auth = auth;
    this.dashboards = dashboards;
    this.activities = activities;
    this.accounts = accounts;
    this.stravaOAuth = stravaOAuth;
    this.googleOAuth = googleOAuth;
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
            platform("COROS", "高驰 COROS", "PASSWORD"),
            platform("IGPSPORT", "iGPSPORT", "PASSWORD"),
            platform("ONELAP", "迈金", "PASSWORD"),
            platform("XINGZHE", "行者", "PASSWORD"),
            platform("GARMIN", "Garmin 中国站", "PASSWORD"),
            platform("STRAVA", "Strava", "OAUTH"));
    return Map.of(
        "wechatLoginEnabled",
        auth.wechatEnabled(),
        "passwordLoginEnabled",
        auth.passwordLoginEnabled(),
        "emailAuthEnabled",
        auth.emailAuthEnabled(),
        "googleLoginEnabled",
        googleOAuth.isConfigured(),
        "devLoginEnabled",
        auth.devEnabled(),
        "stravaOAuthConfigured",
        stravaOAuth.isConfigured(),
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

  private static Map<String, Object> platform(String code, String name, String authMode) {
    return Map.of(
        "code",
        code,
        "name",
        name,
        "authMode",
        authMode,
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

  public record Register(
      String username, String email, String code, String password, String displayName) {
    @Override
    public String toString() {
      return "Register[凭据已隐藏]";
    }
  }

  public record PasswordLogin(
      String username, String account, String email, String code, String password) {
    @Override
    public String toString() {
      return "PasswordLogin[凭据已隐藏]";
    }
  }

  public record EmailCodeRequest(String email, String purpose) {}

  public record EmailBindRequest(String email, String code) {}

  public record GoogleOAuthStart(String redirectUri, String returnUrl) {}

  public record GoogleTicketExchange(String ticket) {
    @Override
    public String toString() {
      return "GoogleTicketExchange[凭据已隐藏]";
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
    if (body.email() != null && !body.email().isBlank()) {
      return auth.registerWithEmail(body.email(), body.code(), body.password(), body.displayName());
    }
    return auth.register(body.username(), body.password(), body.displayName());
  }

  @PostMapping("/auth/login")
  public Map<String, Object> passwordLogin(@RequestBody PasswordLogin body) {
    if (body.code() != null && !body.code().isBlank()) {
      String mail =
          (body.email() != null && !body.email().isBlank()) ? body.email() : body.account();
      return auth.emailLogin(mail, body.code());
    }
    String acct =
        (body.account() != null && !body.account().isBlank()) ? body.account() : body.username();
    return auth.passwordLogin(acct, body.password());
  }

  @PostMapping("/auth/email/code")
  public Map<String, Object> sendEmailCode(
      HttpServletRequest request, @RequestBody EmailCodeRequest body) {
    String authHeader = request.getHeader("Authorization");
    String userId =
        (authHeader != null && !authHeader.isBlank()) ? auth.authenticate(authHeader) : null;
    return auth.sendEmailCode(userId, body.email(), body.purpose());
  }

  @PostMapping("/auth/email/bind")
  public Map<String, Object> bindEmail(
      HttpServletRequest request, @RequestBody EmailBindRequest body) {
    return auth.bindEmail(user(request), body.email(), body.code());
  }

  @PostMapping("/auth/google/start")
  public Map<String, Object> startGoogleLogin(
      HttpServletRequest request, @RequestBody(required = false) GoogleOAuthStart body) {
    var req = body == null ? new GoogleOAuthStart(null, null) : body;
    return googleOAuth.startLogin(req.redirectUri(), req.returnUrl(), request);
  }

  @PostMapping("/auth/google/bind/start")
  public Map<String, Object> startGoogleBind(
      HttpServletRequest request, @RequestBody(required = false) GoogleOAuthStart body) {
    var req = body == null ? new GoogleOAuthStart(null, null) : body;
    return googleOAuth.startBind(user(request), req.redirectUri(), req.returnUrl(), request);
  }

  @GetMapping(value = "/auth/google/callback", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> googleOAuthCallback(
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String error) {
    var outcome = googleOAuth.completeCallback(state, code, error);
    if (outcome.returnUrl() != null) {
      String sep = outcome.returnUrl().contains("?") ? "&" : "?";
      String target;
      if (outcome.ok() && "LOGIN".equals(outcome.mode()) && outcome.ticket() != null) {
        target =
            outcome.returnUrl()
                + sep
                + "googleTicket="
                + URLEncoder.encode(outcome.ticket(), StandardCharsets.UTF_8);
      } else {
        target =
            outcome.returnUrl()
                + sep
                + "googleOAuth="
                + (outcome.ok() ? "success" : "error")
                + (outcome.ok()
                    ? ""
                    : "&googleOAuthMsg="
                        + URLEncoder.encode(outcome.message(), StandardCharsets.UTF_8));
      }
      return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }
    String title = outcome.ok() ? "Google 授权成功" : "Google 授权未完成";
    return oauthResultPage(outcome.ok(), title, outcome.message());
  }

  @PostMapping("/auth/google/exchange")
  public Map<String, Object> exchangeGoogleTicket(
      @RequestBody(required = false) GoogleTicketExchange body) {
    return googleOAuth.exchangeTicket(body == null ? null : body.ticket());
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
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String query,
      @RequestParam(defaultValue = "ALL") String filter,
      @RequestParam(required = false) String platform,
      @RequestParam(defaultValue = "NEWEST") String sort) {
    validatePage(page);
    return activities.page(user(request), page, size, query, filter, platform, sort);
  }

  public record PullActivities(
      List<String> accountIds,
      List<String> platforms,
      Integer historyDays,
      List<String> sportTypes) {}

  @PostMapping("/activities/pull")
  public Map<String, Object> pullActivities(
      HttpServletRequest request, @RequestBody(required = false) PullActivities body) {
    var req = body == null ? new PullActivities(null, null, null, null) : body;
    return jobs.pullFromPlatforms(
        user(request),
        req.accountIds(),
        req.platforms(),
        req.historyDays() == null ? 90 : req.historyDays(),
        req.sportTypes());
  }

  @GetMapping("/activities/pull")
  public Map<String, Object> pullStatus(
      HttpServletRequest request, @RequestParam(required = false) List<String> jobIds) {
    return jobs.pullStatus(user(request), jobIds);
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

  @DeleteMapping("/activities/{id}")
  public Map<String, Object> deleteActivity(HttpServletRequest request, @PathVariable String id) {
    activities.delete(user(request), id);
    return Map.of("success", true);
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

  public record ActivityReconcile(Boolean force) {}

  @PostMapping("/activities/{id}/review")
  public Map<String, Object> review(
      HttpServletRequest request, @PathVariable String id, @RequestBody ActivityReview body) {
    var result = activities.review(user(request), id, body.decision());
    jobs.plan(user(request), result.get("activityId").toString());
    return result;
  }

  @PostMapping("/activities/{id}/reconcile")
  public Map<String, Object> reconcileActivity(
      HttpServletRequest request,
      @PathVariable String id,
      @RequestBody(required = false) ActivityReconcile body) {
    boolean force = body != null && Boolean.TRUE.equals(body.force());
    return jobs.reconcileActivityPlatforms(user(request), id, force);
  }

  @GetMapping("/accounts")
  public List<Map<String, Object>> accounts(HttpServletRequest request) {
    return accounts.list(user(request));
  }

  public record StravaOAuthStart(
      String clientId, String clientSecret, String redirectUri, String returnUrl) {
    @Override
    public String toString() {
      return "StravaOAuthStart[凭据已隐藏]";
    }
  }

  @PostMapping("/accounts/oauth/strava/start")
  public Map<String, Object> startStravaOAuth(
      HttpServletRequest request,
      @RequestBody(required = false) StravaOAuthStart body) {
    var req = body == null ? new StravaOAuthStart(null, null, null, null) : body;
    return stravaOAuth.start(
        user(request),
        req.clientId(),
        req.clientSecret(),
        req.redirectUri(),
        req.returnUrl(),
        request);
  }

  @GetMapping("/accounts/oauth/strava/{challengeId}")
  public Map<String, Object> pollStravaOAuth(
      HttpServletRequest request, @PathVariable String challengeId) {
    return stravaOAuth.status(user(request), challengeId);
  }

  @GetMapping(value = "/accounts/oauth/strava/callback", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> stravaOAuthCallback(
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) String error) {
    var outcome = stravaOAuth.completeCallback(state, code, scope, error);
    if (outcome.returnUrl() != null) {
      String sep = outcome.returnUrl().contains("?") ? "&" : "?";
      String target =
          outcome.returnUrl()
              + sep
              + "stravaOAuth="
              + (outcome.ok() ? "success" : "error")
              + (outcome.ok()
                  ? ""
                  : "&stravaOAuthMsg="
                      + URLEncoder.encode(outcome.message(), StandardCharsets.UTF_8));
      return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }
    String title = outcome.ok() ? "Strava 授权成功" : "Strava 授权未完成";
    return oauthResultPage(outcome.ok(), title, outcome.message());
  }

  private static ResponseEntity<String> oauthResultPage(
      boolean ok, String title, String message) {
    String badgeColor = ok ? "#15803d" : "#b91c1c";
    String html =
        "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>"
            + HtmlUtils.htmlEscape(title)
            + " · FitSync</title>"
            + "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 108 108'%3E%3Crect width='108' height='108' rx='24' fill='%23111827'/%3E%3Cpath d='M24 69 45 38 58 52 76 30 84 37 59 67 47 53 33 75Z' fill='%23F97316'/%3E%3Ccircle cx='30' cy='72' r='7' fill='%23fff'/%3E%3Ccircle cx='79' cy='34' r='7' fill='%2322C55E'/%3E%3C/svg%3E\">"
            + "</head>"
            + "<body style=\"font-family:system-ui,-apple-system,sans-serif;background:#f4f5f7;padding:32px;color:#111827;\">"
            + "<div style=\"max-width:460px;margin:48px auto;background:#fff;border-radius:10px;padding:28px;border:1px solid #e5e7eb;\">"
            + "<div style=\"display:flex;align-items:center;gap:10px;margin:0 0 18px;\">"
            + "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\" width=\"32\" height=\"32\" aria-hidden=\"true\">"
            + "<rect width=\"108\" height=\"108\" rx=\"24\" fill=\"#111827\"/>"
            + "<path d=\"M24 69 45 38 58 52 76 30 84 37 59 67 47 53 33 75Z\" fill=\"#F97316\"/>"
            + "<circle cx=\"30\" cy=\"72\" r=\"7\" fill=\"#fff\"/>"
            + "<circle cx=\"79\" cy=\"34\" r=\"7\" fill=\"#22C55E\"/>"
            + "</svg>"
            + "<strong style=\"font-size:18px;letter-spacing:-0.2px;color:#111827;\">FitSync</strong>"
            + "</div>"
            + "<h2 style=\"margin:0 0 12px;color:"
            + badgeColor
            + ";\">"
            + HtmlUtils.htmlEscape(title)
            + "</h2>"
            + "<p style=\"margin:0 0 16px;line-height:1.6;color:#374151;\">"
            + HtmlUtils.htmlEscape(message)
            + "</p>"
            + "<p style=\"margin:0;font-size:13px;color:#6b7280;\">现在可以关闭此网页并回到 FitSync 页面或微信小程序。</p>"
            + "</div></body></html>";
    return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
        .contentType(MediaType.TEXT_HTML)
        .body(html);
  }

  public record Connect(String platform, String username, String password, boolean storeCredentials) {
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

  public record WebSessionConnect(
      String platform, String displayName, Map<String, String> session) {
    @Override
    public String toString() {
      return "WebSessionConnect[凭据已隐藏]";
    }
  }

  @PostMapping("/accounts/web-session")
  public Map<String, Object> webSessionAccount(
      HttpServletRequest request, @RequestBody WebSessionConnect body) {
    return accounts.connectWeb(user(request), body.platform(), body.displayName(), body.session());
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
      boolean includeHistory,
      List<String> sportTypes) {}

  @PostMapping("/rules")
  public Map<String, Object> rule(HttpServletRequest request, @RequestBody Rule body) {
    if (body.sourcePlatform() == null) throw ApiException.bad("请选择来源平台");
    return jobs.rule(
        user(request),
        body.sourcePlatform().toUpperCase(Locale.ROOT),
        body.destinationAccountId(),
        body.enabled(),
        body.includeHistory(),
        body.sportTypes());
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
      Integer historyDays,
      List<String> sportTypes) {}

  @PostMapping("/sync")
  public Map<String, Object> sync(HttpServletRequest request, @RequestBody Sync body) {
    return jobs.sync(
        user(request),
        body.sourcePlatform(),
        body.destinationAccountIds(),
        body.activityIds(),
        body.historyDays() == null ? 90 : body.historyDays(),
        body.sportTypes());
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
