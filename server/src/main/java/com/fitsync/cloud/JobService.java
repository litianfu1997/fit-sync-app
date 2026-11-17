package com.fitsync.cloud;

import com.fitsync.cloud.connector.*;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JobService {
  private final CloudStore store;
  private final ActivityService activities;
  private final AccountService accounts;
  private final boolean enabled;
  private final long interval;
  private final String worker = CloudStore.id();
  private final TransactionTemplate enqueueTransaction;
  private final ExecutorService pullExecutor;
  private static final long LEASE = Duration.ofMinutes(15).toMillis();
  private static final int MAX_PULL_PAGES = 20;
  private static final Set<String> PERMANENT_REMOTE_SKIP_CODES =
      Set.of(
          "NO_ORIGINAL_FILE",
          "UNSUPPORTED_ORIGINAL_FORMAT",
          "INVALID_FIT",
          "INVALID_ORIGINAL_FILE",
          "IMPORT_INVALID");

  public JobService(
      CloudStore store,
      ActivityService activities,
      AccountService accounts,
      PlatformTransactionManager transactions,
      @Value("${fitsync.scheduler-enabled:true}") boolean enabled,
      @Value("${fitsync.sync-interval-ms:900000}") long interval) {
    this.store = store;
    this.activities = activities;
    this.accounts = accounts;
    this.enabled = enabled;
    this.interval = interval;
    this.enqueueTransaction = new TransactionTemplate(transactions);
    this.enqueueTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    this.pullExecutor =
        enabled
            ? Executors.newFixedThreadPool(
                2,
                r -> {
                  Thread t = new Thread(r, "fitsync-pull");
                  t.setDaemon(true);
                  return t;
                })
            : null;
  }

  @PreDestroy
  public void shutdown() {
    if (pullExecutor != null) pullExecutor.shutdownNow();
  }

  public List<Map<String, Object>> rules(String user) {
    return store
        .db
        .queryForList("SELECT * FROM sync_rule WHERE user_id=? ORDER BY created_at", user)
        .stream()
        .map(
            row ->
                Map.<String, Object>of(
                    "id",
                    row.get("id"),
                    "sourcePlatform",
                    row.get("source_platform"),
                    "destinationAccountId",
                    row.get("destination_account_id"),
                    "enabled",
                    row.get("enabled"),
                    "includeHistory",
                    row.get("include_history")))
        .toList();
  }

  @Transactional
  public synchronized Map<String, Object> rule(
      String user, String source, String destination, boolean enabled, boolean history) {
    store.db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", String.class, user);
    var target = store.owned("platform_account", destination, user);
    if (!source.equals("LOCAL")) {
      accounts.connector(source);
      if (store.db.queryForObject(
              "SELECT COUNT(*) FROM platform_account WHERE user_id=? AND platform=?",
              Integer.class,
              user,
              source)
          == 0) throw ApiException.bad("请先连接来源平台");
    }
    if (source.equals(target.get("platform"))) throw ApiException.bad("来源和目标不能相同");
    var rows =
        store.db.queryForList(
            "SELECT id FROM sync_rule WHERE user_id=? AND source_platform=? AND"
                + " destination_account_id=?",
            user,
            source,
            destination);
    String id = rows.isEmpty() ? CloudStore.id() : CloudStore.str(rows.getFirst(), "id");
    if (rows.isEmpty())
      store.db.update(
          "INSERT INTO"
              + " sync_rule(id,user_id,source_platform,destination_account_id,enabled,include_history,created_at)"
              + " VALUES(?,?,?,?,?,?,?)",
          id,
          user,
          source,
          destination,
          enabled,
          history,
          System.currentTimeMillis());
    else
      store.db.update(
          "UPDATE sync_rule SET enabled=?,include_history=? WHERE id=? AND user_id=?",
          enabled,
          history,
          id,
          user);
    if (enabled && history) {
      for (var activity :
          store.db.queryForList(
              "SELECT a.id FROM activity a WHERE a.user_id=? AND a.needs_review=FALSE AND"
                  + " (a.source_platform=? OR EXISTS(SELECT 1 FROM platform_copy p JOIN"
                  + " platform_account c ON c.id=p.account_id WHERE p.activity_id=a.id AND"
                  + " p.user_id=a.user_id AND c.platform=?))",
              user,
              source,
              source)) delivery(user, CloudStore.str(activity, "id"), destination, id);
    }
    return Map.of(
        "id",
        id,
        "sourcePlatform",
        source,
        "destinationAccountId",
        destination,
        "enabled",
        enabled,
        "includeHistory",
        history);
  }

  public void deleteRule(String user, String id) {
    store.owned("sync_rule", id, user);
    store.db.update("DELETE FROM sync_rule WHERE id=? AND user_id=?", id, user);
  }

  @Transactional
  public Map<String, Object> pullFromPlatforms(
      String user, List<String> accountIds, List<String> platforms, int historyDays) {
    store.db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", String.class, user);
    if (historyDays < 1 || historyDays > 365) throw ApiException.bad("回填范围为 1 至 365 天");
    var allAccounts =
        store.db.queryForList(
            "SELECT * FROM platform_account WHERE user_id=? ORDER BY platform", user);
    if (allAccounts.isEmpty()) throw ApiException.bad("请先连接至少一个运动平台账号");

    Set<String> targetAccountIds =
        accountIds == null
            ? Set.of()
            : accountIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toSet());
    for (String reqId : targetAccountIds) store.owned("platform_account", reqId, user);

    Set<String> targetPlatforms =
        platforms == null
            ? Set.of()
            : platforms.stream()
                .filter(Objects::nonNull)
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toSet());

    var selectedAccounts = new ArrayList<Map<String, Object>>();
    for (var acc : allAccounts) {
      String id = CloudStore.str(acc, "id");
      String platform = CloudStore.str(acc, "platform");
      boolean matchId = targetAccountIds.isEmpty() || targetAccountIds.contains(id);
      boolean matchPlatform = targetPlatforms.isEmpty() || targetPlatforms.contains(platform);
      if (matchId && matchPlatform) selectedAccounts.add(acc);
    }
    if (selectedAccounts.isEmpty()) throw ApiException.bad("未找到匹配的已连接平台账号");

    List<Map<String, Object>> startedAccounts = new ArrayList<>();
    List<Map<String, Object>> skippedPlatforms = new ArrayList<>();
    List<String> jobIds = new ArrayList<>();
    List<String> dispatchIds = new ArrayList<>();
    long now = System.currentTimeMillis();

    for (var acc : selectedAccounts) {
      String accountId = CloudStore.str(acc, "id");
      String platform = CloudStore.str(acc, "platform");
      String displayName = CloudStore.str(acc, "display_name");
      if (accounts.connector(platform).skipPullAsDestination(accounts.session(acc))) {
        skippedPlatforms.add(
            Map.of(
                "accountId",
                accountId,
                "platform",
                platform,
                "displayName",
                displayName == null ? platform : displayName,
                "reason",
                "Strava 官方 OAuth 授权暂不支持导出原始 FIT 文件，可用于接收其他平台同步"));
        continue;
      }
      var activePulls =
          store.db.queryForList(
              "SELECT id,status FROM sync_job WHERE user_id=? AND account_id=? AND kind='PULL' AND"
                  + " status IN ('PENDING','RUNNING','RETRY') ORDER BY created_at DESC LIMIT 1",
              user,
              accountId);
      String jobId;
      String status;
      if (!activePulls.isEmpty()) {
        jobId = CloudStore.str(activePulls.getFirst(), "id");
        status = CloudStore.str(activePulls.getFirst(), "status");
        if (!"RUNNING".equals(status)) {
          store.db.update(
              "UPDATE sync_job SET status='PENDING',next_run_at=?,updated_at=? WHERE id=? AND"
                  + " user_id=?",
              now,
              now,
              jobId,
              user);
          status = "PENDING";
          dispatchIds.add(jobId);
        }
      } else {
        jobId = pull(user, accountId, historyDays, List.of(), null);
        status = "PENDING";
        dispatchIds.add(jobId);
      }
      jobIds.add(jobId);
      startedAccounts.add(
          Map.of(
              "accountId",
              accountId,
              "platform",
              platform,
              "displayName",
              displayName == null ? platform : displayName,
              "jobId",
              jobId,
              "status",
              status));
    }
    if (jobIds.isEmpty()) {
      String reason =
          skippedPlatforms.isEmpty()
              ? "没有可拉取活动的平台账号"
              : skippedPlatforms.getFirst().get("reason").toString();
      throw ApiException.bad(reason);
    }
    dispatchPull(dispatchIds);
    return Map.of(
        "jobIds", jobIds, "accounts", startedAccounts, "skippedPlatforms", skippedPlatforms);
  }

  public Map<String, Object> pullStatus(String user, List<String> jobIds) {
    List<Map<String, Object>> rows;
    if (jobIds != null && !jobIds.isEmpty()) {
      List<String> cleanIds =
          jobIds.stream()
              .filter(Objects::nonNull)
              .map(String::trim)
              .filter(s -> !s.isBlank())
              .distinct()
              .limit(20)
              .toList();
      if (cleanIds.isEmpty()) return Map.of("jobs", List.of(), "activeCount", 0, "totals", zeroProgress());
      String placeholders = String.join(",", Collections.nCopies(cleanIds.size(), "?"));
      List<Object> args = new ArrayList<>(cleanIds.size() + 1);
      args.add(user);
      args.addAll(cleanIds);
      rows =
          store.db.queryForList(
              "SELECT j.*,a.platform,a.display_name,a.status AS account_status FROM sync_job j"
                  + " JOIN platform_account a ON a.id=j.account_id WHERE j.user_id=? AND"
                  + " j.kind='PULL' AND j.id IN ("
                  + placeholders
                  + ") ORDER BY j.created_at DESC",
              args.toArray());
    } else {
      long cutoff = System.currentTimeMillis() - Duration.ofMinutes(15).toMillis();
      rows =
          store.db.queryForList(
              "SELECT j.*,a.platform,a.display_name,a.status AS account_status FROM sync_job j"
                  + " JOIN platform_account a ON a.id=j.account_id WHERE j.user_id=? AND"
                  + " j.kind='PULL' AND (j.status IN ('PENDING','RUNNING','RETRY') OR j.updated_at>=?)"
                  + " ORDER BY j.created_at DESC LIMIT 10",
              user,
              cutoff);
    }
    long totalFetched = 0,
        totalExisting = 0,
        totalImported = 0,
        totalMerged = 0,
        totalReview = 0,
        totalSkipped = 0,
        totalPages = 0;
    int activeCount = 0;
    List<Map<String, Object>> jobs = new ArrayList<>(rows.size());
    for (var row : rows) {
      String status = CloudStore.str(row, "status");
      if (Set.of("PENDING", "RUNNING", "RETRY").contains(status)) activeCount++;
      Map<String, Object> payload = Map.of();
      String rawPayload = CloudStore.str(row, "payload_json");
      if (rawPayload != null && !rawPayload.isBlank()) {
        try {
          payload = store.decode(rawPayload);
        } catch (Exception ignored) {
        }
      }
      long fetched = CloudStore.number(payload, "fetched");
      long existingSkipped = CloudStore.number(payload, "existingSkipped");
      long imported = CloudStore.number(payload, "imported");
      long merged = CloudStore.number(payload, "merged");
      long needsReview = CloudStore.number(payload, "needsReview");
      long skippedNoFit =
          payload.containsKey("skippedNoFit")
              ? CloudStore.number(payload, "skippedNoFit")
              : CloudStore.number(payload, "skipped");
      long pages = CloudStore.number(payload, "pages");
      totalFetched += fetched;
      totalExisting += existingSkipped;
      totalImported += imported;
      totalMerged += merged;
      totalReview += needsReview;
      totalSkipped += skippedNoFit;
      totalPages += pages;

      var progress = new LinkedHashMap<String, Object>();
      progress.put("fetched", fetched);
      progress.put("existingSkipped", existingSkipped);
      progress.put("imported", imported);
      progress.put("merged", merged);
      progress.put("needsReview", needsReview);
      progress.put("skippedNoFit", skippedNoFit);
      progress.put("pages", pages);

      var item = new LinkedHashMap<String, Object>();
      item.put("id", row.get("id"));
      item.put("accountId", row.get("account_id"));
      item.put("platform", row.get("platform"));
      item.put("displayName", row.get("display_name"));
      item.put("accountStatus", row.get("account_status"));
      item.put("status", status);
      item.put("message", row.get("message"));
      item.put("progress", progress);
      item.put("createdAtEpochMs", row.get("created_at"));
      item.put("updatedAtEpochMs", row.get("updated_at"));
      jobs.add(item);
    }
    var totals = new LinkedHashMap<String, Object>();
    totals.put("fetched", totalFetched);
    totals.put("existingSkipped", totalExisting);
    totals.put("imported", totalImported);
    totals.put("merged", totalMerged);
    totals.put("needsReview", totalReview);
    totals.put("skippedNoFit", totalSkipped);
    totals.put("pages", totalPages);
    return Map.of("jobs", jobs, "activeCount", activeCount, "totals", totals);
  }

  private static Map<String, Object> zeroProgress() {
    var totals = new LinkedHashMap<String, Object>();
    totals.put("fetched", 0L);
    totals.put("existingSkipped", 0L);
    totals.put("imported", 0L);
    totals.put("merged", 0L);
    totals.put("needsReview", 0L);
    totals.put("skippedNoFit", 0L);
    totals.put("pages", 0L);
    return totals;
  }

  private void dispatchPull(List<String> jobIds) {
    if (!enabled || pullExecutor == null || jobIds.isEmpty()) return;
    List<String> ids = List.copyOf(jobIds);
    Runnable submit =
        () -> {
          for (String jobId : ids) {
            pullExecutor.submit(
                () -> {
                  try {
                    runOne(jobId);
                  } catch (Exception ignored) {
                  }
                });
          }
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              submit.run();
            }
          });
    } else {
      submit.run();
    }
  }

  @Transactional
  public Map<String, Object> sync(
      String user,
      String source,
      List<String> destinations,
      List<String> activityIds,
      int historyDays) {
    store.db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", String.class, user);
    if (historyDays < 1 || historyDays > 365) throw ApiException.bad("回填范围为 1 至 365 天");
    if (destinations == null) destinations = List.of();
    if (destinations.size() > 6) throw ApiException.bad("目标账号数量无效");
    for (String target : destinations) store.owned("platform_account", target, user);
    boolean selected = activityIds != null && !activityIds.isEmpty();
    String sourceAccount = null;
    if (selected) {
      if (activityIds.size() > 100) throw ApiException.bad("每次最多选择 100 条活动");
      for (String activity : activityIds) store.owned("activity", activity, user);
    } else if ("LOCAL".equals(source)) {
      activityIds =
          store
              .db
              .queryForList(
                  "SELECT id FROM activity WHERE user_id=? AND source_platform='LOCAL' AND"
                      + " needs_review=FALSE ORDER BY start_time DESC LIMIT 101",
                  user)
              .stream()
              .map(row -> CloudStore.str(row, "id"))
              .toList();
      if (activityIds.isEmpty()) throw ApiException.bad("请先导入 FIT 文件");
      if (activityIds.size() > 100) throw ApiException.bad("本地活动超过 100 条，请从活动列表选择分批同步");
      selected = true;
    } else {
      var sourceAcc =
          store.one("SELECT * FROM platform_account WHERE user_id=? AND platform=?", user, source);
      sourceAccount = CloudStore.str(sourceAcc, "id");
      if (accounts
          .connector(source)
          .skipPullAsDestination(accounts.session(sourceAcc))) {
        throw ApiException.bad("Strava 官方 OAuth 授权暂不支持导出原始 FIT 文件，可用于接收其他平台或本地活动同步");
      }
    }
    if (selected && destinations.isEmpty()) throw ApiException.bad("请选择目标账号");
    List<String> ids = new ArrayList<>();
    List<String> pullIds = new ArrayList<>();
    // 目标预拉取先入队；所有拉取完成前不投递，减少跨平台循环。对纯 OAuth 不可导出原文件的目标跳过以节省 API 配额。
    for (String target : destinations) {
      if (!shouldSkipPull(user, target)) {
        String pullId = pull(user, target, historyDays, List.of(), null);
        ids.add(pullId);
        pullIds.add(pullId);
      }
    }
    if (selected) {
      for (String id : activityIds) {
        store.owned("activity", id, user);
        for (String target : destinations) {
          String job = delivery(user, id, target);
          if (job != null) ids.add(job);
        }
      }
    } else {
      String pullId = pull(user, sourceAccount, historyDays, destinations, null);
      ids.add(pullId);
      pullIds.add(pullId);
    }
    dispatchPull(pullIds);
    return Map.of("jobIds", ids);
  }

  public void plan(String user, String activityId) {
    var activity = store.owned("activity", activityId, user);
    if (Boolean.TRUE.equals(activity.get("needs_review"))) return;
    for (var rule :
        store.db.queryForList(
            "SELECT r.* FROM sync_rule r WHERE r.user_id=? AND r.enabled=TRUE AND"
                + " (r.source_platform=? OR EXISTS(SELECT 1 FROM platform_copy p JOIN"
                + " platform_account c ON c.id=p.account_id WHERE p.activity_id=? AND"
                + " p.user_id=r.user_id AND c.platform=r.source_platform))",
            user,
            activity.get("source_platform"),
            activityId)) {
      if (Boolean.TRUE.equals(rule.get("include_history"))
          || CloudStore.number(activity, "start_time") >= CloudStore.number(rule, "created_at"))
        delivery(
            user,
            activityId,
            CloudStore.str(rule, "destination_account_id"),
            CloudStore.str(rule, "id"));
    }
  }

  private String pull(
      String user, String account, int days, List<String> destinations, String stableKey) {
    var a = store.owned("platform_account", account, user);
    long now = System.currentTimeMillis();
    long since =
        stableKey != null && a.get("last_sync_at") instanceof Number n
            ? Math.max(
                now - Duration.ofDays(days).toMillis(),
                n.longValue() - Duration.ofHours(48).toMillis())
            : now - Duration.ofDays(days).toMillis();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("since", since);
    payload.put("destinations", destinations);
    payload.put("cursor", null);
    payload.put("fetched", 0L);
    payload.put("existingSkipped", 0L);
    payload.put("imported", 0L);
    payload.put("merged", 0L);
    payload.put("needsReview", 0L);
    payload.put("skippedNoFit", 0L);
    payload.put("pages", 0L);
    return enqueue(
        user,
        "PULL",
        account,
        null,
        stableKey == null ? "pull:" + CloudStore.id() : stableKey,
        payload);
  }

  public String delivery(String user, String activityId, String accountId) {
    return delivery(user, activityId, accountId, null);
  }

  private String delivery(String user, String activityId, String accountId, String ruleId) {
    var activity = store.owned("activity", activityId, user);
    store.owned("platform_account", accountId, user);
    if (Boolean.TRUE.equals(activity.get("needs_review"))) return null;
    if (store.db.queryForObject(
            "SELECT COUNT(*) FROM platform_copy WHERE user_id=? AND activity_id=? AND account_id=?",
            Integer.class,
            user,
            activityId,
            accountId)
        > 0) return null;
    var variant =
        store.one(
            "SELECT id,sha256 FROM activity_variant WHERE user_id=? AND id=?",
            user,
            activity.get("primary_variant_id"));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("variantId", variant.get("id"));
    payload.put("sha256", variant.get("sha256"));
    if (ruleId != null) payload.put("ruleId", ruleId);
    String id =
        enqueue(
            user,
            "DELIVERY",
            accountId,
            activityId,
            "delivery:" + variant.get("sha256") + ":" + accountId,
            payload);
    store.db.update(
        "UPDATE sync_job SET"
            + " status='PENDING',payload_json=?,next_run_at=?,attempt_count=0,message=NULL WHERE"
            + " id=? AND user_id=? AND status='CANCELLED' AND confirmation_json IS NULL",
        store.encode(payload),
        System.currentTimeMillis(),
        id,
        user);
    if (ruleId == null) {
      var old =
          store.decode(
              CloudStore.str(
                  store.one("SELECT payload_json FROM sync_job WHERE id=? AND user_id=?", id, user),
                  "payload_json"));
      old.remove("ruleId");
      store.db.update(
          "UPDATE sync_job SET payload_json=? WHERE id=? AND user_id=? AND status IN"
              + " ('PENDING','RETRY')",
          store.encode(old),
          id,
          user);
    }
    return id;
  }

  private String enqueue(
      String user, String kind, String account, String activity, String key, Object payload) {
    String id = CloudStore.id();
    long now = System.currentTimeMillis();
    try {
      enqueueTransaction.executeWithoutResult(
          tx ->
              store.db.update(
                  "INSERT INTO"
                      + " sync_job(id,user_id,kind,status,account_id,activity_id,idempotency_key,payload_json,attempt_count,next_run_at,created_at,updated_at)"
                      + " VALUES(?,?,?,'PENDING',?,?,?,?,0,?,?,?)",
                  id,
                  user,
                  kind,
                  account,
                  activity,
                  key,
                  store.encode(payload),
                  now,
                  now,
                  now));
      return id;
    } catch (DuplicateKeyException e) {
      // 唯一键冲突先回滚到 JDBC 保存点，避免 PostgreSQL 外层事务进入 aborted 状态。
      if (activity != null)
        return CloudStore.str(
            store.one(
                "SELECT id FROM sync_job WHERE activity_id=? AND account_id=? AND kind=? AND"
                    + " user_id=?",
                activity,
                account,
                kind,
                user),
            "id");
      return CloudStore.str(
          store.one("SELECT id FROM sync_job WHERE idempotency_key=? AND user_id=?", key, user),
          "id");
    }
  }

  public Map<String, Object> page(String user, int page, int size) {
    int limit = Math.clamp(size, 1, 100);
    var rows =
        store.db.queryForList(
            "SELECT j.*,a.platform,v.title AS activity_title FROM sync_job j JOIN platform_account"
                + " a ON a.id=j.account_id LEFT JOIN activity v ON v.id=j.activity_id WHERE"
                + " j.user_id=? ORDER BY j.created_at DESC LIMIT ? OFFSET ?",
            user,
            limit,
            Math.max(0, page) * limit);
    return Map.of(
        "items",
        rows.stream()
            .map(
                row -> {
                  var r = new LinkedHashMap<String, Object>();
                  r.put("id", row.get("id"));
                  r.put("kind", row.get("kind"));
                  r.put("status", row.get("status"));
                  r.put("platform", row.get("platform"));
                  r.put("activityTitle", row.get("activity_title"));
                  r.put("message", row.get("message"));
                  r.put("createdAtEpochMs", row.get("created_at"));
                  r.put("updatedAtEpochMs", row.get("updated_at"));
                  return r;
                })
            .toList(),
        "total",
        store.db.queryForObject("SELECT COUNT(*) FROM sync_job WHERE user_id=?", Long.class, user));
  }

  public void retry(String user, String id) {
    var job = store.owned("sync_job", id, user);
    String state = CloudStore.str(job, "status");
    if (!Set.of("FAILED", "RETRY", "WAITING_USER").contains(state))
      throw ApiException.bad("当前任务无需重试");
    String token = CloudStore.str(job, "confirmation_json");
    if ("UPLOAD_STARTED".equals(token)) throw ApiException.bad("该上传结果未知，请先在目标平台核对；为防止重复活动，不会再次上传");
    store.db.update(
        "UPDATE sync_job SET status=?,next_run_at=?,attempt_count=0,message=NULL WHERE id=? AND"
            + " user_id=?",
        token == null ? "PENDING" : "CONFIRMING",
        System.currentTimeMillis(),
        id,
        user);
  }

  @Scheduled(fixedDelayString = "${fitsync.sync-interval-ms:900000}", initialDelay = 15000)
  public void scheduleRules() {
    if (!enabled) return;
    long bucket = System.currentTimeMillis() / Math.max(900000, interval);
    for (var rule :
        store.db.queryForList(
            "SELECT r.*,a.platform AS destination_platform FROM sync_rule r JOIN platform_account a"
                + " ON a.id=r.destination_account_id WHERE r.enabled=TRUE AND"
                + " a.status='CONNECTED'")) {
      String user = CloudStore.str(rule, "user_id"),
          dest = CloudStore.str(rule, "destination_account_id");
      if (!shouldSkipPull(user, dest)) {
        pull(user, dest, 90, List.of(), "auto-pull:" + dest + ":" + bucket);
      }
      for (var source :
          store.db.queryForList(
              "SELECT id FROM platform_account WHERE user_id=? AND platform=? AND"
                  + " status='CONNECTED'",
              user,
              rule.get("source_platform"))) {
        String id = CloudStore.str(source, "id");
        if (!shouldSkipPull(user, id)) {
          pull(user, id, 90, List.of(), "auto-pull:" + id + ":" + bucket);
        }
      }
    }
    store.db.update("DELETE FROM user_session WHERE expires_at<?", System.currentTimeMillis());
  }

  private boolean shouldSkipPull(String user, String accountId) {
    var account = store.owned("platform_account", accountId, user);
    String platform = CloudStore.str(account, "platform");
    return accounts.connector(platform).skipPullAsDestination(accounts.session(account));
  }

  @Scheduled(fixedDelay = 3000, initialDelay = 3000)
  public void work() {
    if (!enabled) return;
    for (var job :
        store.db.queryForList(
            "SELECT * FROM sync_job WHERE status IN ('PENDING','RETRY','CONFIRMING','RUNNING') AND"
                + " next_run_at<=? AND (lease_until IS NULL OR lease_until<?) ORDER BY CASE WHEN"
                + " kind='PULL' THEN 0 ELSE 1 END,created_at LIMIT 12",
            System.currentTimeMillis(),
            System.currentTimeMillis())) runOne(CloudStore.str(job, "id"));
  }

  public void runOne(String id) {
    var rows = store.db.queryForList("SELECT * FROM sync_job WHERE id=?", id);
    if (rows.isEmpty()) return;
    var job = rows.getFirst();
    String user = CloudStore.str(job, "user_id"),
        accountId = CloudStore.str(job, "account_id"),
        previous = CloudStore.str(job, "status");
    long now = System.currentTimeMillis();
    if (!Set.of("PENDING", "RETRY", "CONFIRMING", "RUNNING").contains(previous)
        || CloudStore.number(job, "next_run_at") > now) return;
    if (job.get("lease_until") instanceof Number lease && lease.longValue() > now) return;
    if (job.get("kind").equals("DELIVERY")
        && store.db.queryForObject(
                "SELECT COUNT(*) FROM sync_job WHERE user_id=? AND kind='PULL' AND status IN"
                    + " ('PENDING','RETRY','RUNNING')",
                Integer.class,
                user)
            > 0) return;
    if (!claimAccount(accountId, now)) return;
    if (store.db.update(
            "UPDATE sync_job SET status='RUNNING',lease_owner=?,lease_until=?,updated_at=? WHERE"
                + " id=? AND (lease_until IS NULL OR lease_until<?) AND status IN"
                + " ('PENDING','RETRY','CONFIRMING','RUNNING')",
            worker,
            now + LEASE,
            now,
            id,
            now)
        == 0) {
      release(accountId);
      return;
    }
    var timer =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "fitsync-lease");
              t.setDaemon(true);
              return t;
            });
    timer.scheduleAtFixedRate(() -> heartbeat(id, accountId), 30, 30, TimeUnit.SECONDS);
    try {
      var account = store.owned("platform_account", accountId, user);
      Session session = accounts.session(account);
      try {
        PlatformConnector connector = accounts.connector(CloudStore.str(account, "platform"));
        if (job.get("kind").equals("PULL")) processPull(job, account, connector, session);
        else processDelivery(job, account, connector, session);
      } finally {
        accounts.saveSession(account, session);
      }
    } catch (Exception e) {
      var fresh = store.one("SELECT confirmation_json FROM sync_job WHERE id=?", id);
      if ("UPLOAD_STARTED".equals(fresh.get("confirmation_json")))
        finish(job, "WAITING_USER", "上传结果未知，请在目标平台核对，系统不会重复上传", 60000);
      else finish(job, "RETRY", "同步遇到临时错误，稍后重试", 60000);
    } finally {
      timer.shutdownNow();
      store.db.update(
          "UPDATE sync_job SET lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=?",
          id,
          worker);
      release(accountId);
    }
  }

  private boolean claimAccount(String id, long now) {
    if (store.db.update(
            "UPDATE account_lease SET lease_owner=?,lease_until=? WHERE account_id=? AND"
                + " lease_until<?",
            worker,
            now + LEASE,
            id,
            now)
        > 0) return true;
    try {
      store.db.update(
          "INSERT INTO account_lease(account_id,lease_owner,lease_until) VALUES(?,?,?)",
          id,
          worker,
          now + LEASE);
      return true;
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      return false;
    }
  }

  private void heartbeat(String job, String account) {
    long until = System.currentTimeMillis() + LEASE;
    store.db.update(
        "UPDATE sync_job SET lease_until=? WHERE id=? AND lease_owner=?", until, job, worker);
    store.db.update(
        "UPDATE account_lease SET lease_until=? WHERE account_id=? AND lease_owner=?",
        until,
        account,
        worker);
  }

  private void release(String account) {
    store.db.update(
        "DELETE FROM account_lease WHERE account_id=? AND lease_owner=?", account, worker);
  }

  private void processPull(
      Map<String, Object> job,
      Map<String, Object> account,
      PlatformConnector connector,
      Session session) {
    String jobId = CloudStore.str(job, "id");
    String user = CloudStore.str(job, "user_id"), accountId = CloudStore.str(account, "id");
    String platform = CloudStore.str(account, "platform");
    var payload = new LinkedHashMap<>(store.decode(CloudStore.str(job, "payload_json")));
    long since = CloudStore.number(payload, "since");
    String cursor = CloudStore.str(payload, "cursor");
    long fetched = CloudStore.number(payload, "fetched");
    long existingSkipped = CloudStore.number(payload, "existingSkipped");
    long imported = CloudStore.number(payload, "imported");
    long merged = CloudStore.number(payload, "merged");
    long needsReview = CloudStore.number(payload, "needsReview");
    long skippedNoFit =
        payload.containsKey("skippedNoFit")
            ? CloudStore.number(payload, "skippedNoFit")
            : CloudStore.number(payload, "skipped");
    long pages = CloudStore.number(payload, "pages");

    for (int page = 0; page < MAX_PULL_PAGES; page++) {
      var result = connector.listActivities(session, since, cursor);
      if (result.status() != ConnectorResult.Status.SUCCESS) {
        savePullProgress(
            jobId,
            payload,
            cursor,
            fetched,
            existingSkipped,
            imported,
            merged,
            needsReview,
            skippedNoFit,
            pages,
            null);
        applyFailure(job, result);
        return;
      }
      pages++;
      var remotes = result.value().items();
      fetched += remotes.size();
      Set<String> knownRemoteIds = findKnownRemoteIds(user, accountId, remotes);

      for (var remote : remotes) {
        if (knownRemoteIds.contains(remote.remoteActivityId())) {
          existingSkipped++;
          continue;
        }
        var downloaded = connector.downloadFit(session, remote);
        if (downloaded.status() == ConnectorResult.Status.PERMANENT_FAILURE) {
          skippedNoFit++;
          String code = downloaded.code();
          if (code == null || PERMANENT_REMOTE_SKIP_CODES.contains(code)) {
            recordRemoteSkip(
                user,
                accountId,
                remote.remoteActivityId(),
                code == null ? "NO_ORIGINAL_FILE" : code,
                downloaded.message());
          }
          continue;
        }
        if (downloaded.status() != ConnectorResult.Status.SUCCESS) {
          savePullProgress(
              jobId,
              payload,
              cursor,
              fetched,
              existingSkipped,
              imported,
              merged,
              needsReview,
              skippedNoFit,
              pages,
              null);
          applyFailure(job, downloaded);
          return;
        }
        Map<String, Object> importedResult;
        try {
          importedResult =
              activities.importFit(
                  user,
                  downloaded.value().bytes(),
                  platform,
                  accountId,
                  remote.remoteActivityId(),
                  remote.name());
        } catch (ApiException invalid) {
          skippedNoFit++;
          recordRemoteSkip(
              user,
              accountId,
              remote.remoteActivityId(),
              "IMPORT_INVALID",
              invalid.getMessage());
          continue;
        }
        String dedupAction = Objects.toString(importedResult.get("dedupAction"), "CREATE_NEW");
        switch (dedupAction) {
          case "DUPLICATE_FILE", "AUTO_MERGE" -> merged++;
          case "NEEDS_REVIEW" -> needsReview++;
          default -> imported++;
        }
        String activity = importedResult.get("activityId").toString();
        plan(user, activity);
        if (payload.get("destinations") instanceof List<?> destinations)
          for (Object target : destinations) delivery(user, activity, target.toString());
      }

      String nextCursor = result.value().nextCursor();
      boolean hasMore =
          result.value().hasMore() && nextCursor != null && !Objects.equals(cursor, nextCursor);
      cursor = hasMore ? nextCursor : null;
      String liveMessage =
          "正在拉取第 "
              + pages
              + " 页（新增 "
              + imported
              + " 条，去重 "
              + merged
              + " 条"
              + (needsReview > 0 ? "，待核对 " + needsReview + " 条" : "")
              + "）";
      savePullProgress(
          jobId,
          payload,
          cursor,
          fetched,
          existingSkipped,
          imported,
          merged,
          needsReview,
          skippedNoFit,
          pages,
          hasMore ? liveMessage : null);
      heartbeat(jobId, accountId);

      if (!hasMore) {
        store.db.update(
            "UPDATE platform_account SET last_sync_at=?,last_error=NULL,status='CONNECTED' WHERE"
                + " id=? AND user_id=?",
            System.currentTimeMillis(),
            accountId,
            user);
        finish(
            job,
            "SUCCEEDED",
            buildPullSummary(fetched, existingSkipped, imported, merged, needsReview, skippedNoFit),
            0);
        return;
      }
    }

    finish(job, "PENDING", "已拉取 " + pages + " 页，继续拉取剩余活动", 0);
  }

  private void savePullProgress(
      String jobId,
      Map<String, Object> payload,
      String cursor,
      long fetched,
      long existingSkipped,
      long imported,
      long merged,
      long needsReview,
      long skippedNoFit,
      long pages,
      String message) {
    payload.put("cursor", cursor);
    payload.put("fetched", fetched);
    payload.put("existingSkipped", existingSkipped);
    payload.put("imported", imported);
    payload.put("merged", merged);
    payload.put("needsReview", needsReview);
    payload.put("skippedNoFit", skippedNoFit);
    payload.put("skipped", skippedNoFit);
    payload.put("pages", pages);
    if (message != null) {
      String trimmed = message.length() > 280 ? message.substring(0, 280) : message;
      store.db.update(
          "UPDATE sync_job SET payload_json=?,message=?,updated_at=? WHERE id=? AND lease_owner=?",
          store.encode(payload),
          trimmed,
          System.currentTimeMillis(),
          jobId,
          worker);
    } else {
      store.db.update(
          "UPDATE sync_job SET payload_json=?,updated_at=? WHERE id=? AND lease_owner=?",
          store.encode(payload),
          System.currentTimeMillis(),
          jobId,
          worker);
    }
  }

  private Set<String> findKnownRemoteIds(
      String user, String accountId, List<RemoteActivity> remotes) {
    if (remotes == null || remotes.isEmpty()) return Set.of();
    List<String> remoteIds =
        remotes.stream()
            .map(RemoteActivity::remoteActivityId)
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();
    if (remoteIds.isEmpty()) return Set.of();
    String placeholders = String.join(",", Collections.nCopies(remoteIds.size(), "?"));
    List<Object> args = new ArrayList<>(remoteIds.size() + 2);
    args.add(user);
    args.add(accountId);
    args.addAll(remoteIds);
    Set<String> known = new HashSet<>();
    for (var row :
        store.db.queryForList(
            "SELECT remote_id FROM platform_copy WHERE user_id=? AND account_id=? AND remote_id"
                + " IN ("
                + placeholders
                + ")",
            args.toArray())) {
      String rid = CloudStore.str(row, "remote_id");
      if (rid != null) known.add(rid);
    }
    for (var row :
        store.db.queryForList(
            "SELECT remote_id FROM remote_activity_skip WHERE user_id=? AND account_id=? AND"
                + " remote_id IN ("
                + placeholders
                + ")",
            args.toArray())) {
      String rid = CloudStore.str(row, "remote_id");
      if (rid != null) known.add(rid);
    }
    return known;
  }

  private void recordRemoteSkip(
      String user, String accountId, String remoteId, String reasonCode, String message) {
    if (remoteId == null || remoteId.isBlank()) return;
    long now = System.currentTimeMillis();
    String safeMsg =
        message == null ? null : (message.length() > 280 ? message.substring(0, 280) : message);
    int updated =
        store.db.update(
            "UPDATE remote_activity_skip SET reason_code=?,message=?,updated_at=? WHERE"
                + " account_id=? AND remote_id=? AND user_id=?",
            reasonCode,
            safeMsg,
            now,
            accountId,
            remoteId,
            user);
    if (updated == 0) {
      try {
        store.db.update(
            "INSERT INTO"
                + " remote_activity_skip(id,user_id,account_id,remote_id,reason_code,message,created_at,updated_at)"
                + " VALUES(?,?,?,?,?,?,?,?)",
            CloudStore.id(),
            user,
            accountId,
            remoteId,
            reasonCode,
            safeMsg,
            now,
            now);
      } catch (DuplicateKeyException ignored) {
      }
    }
  }

  private static String buildPullSummary(
      long fetched,
      long existingSkipped,
      long imported,
      long merged,
      long needsReview,
      long skippedNoFit) {
    if (fetched == 0) return "活动拉取完成，该时间范围内暂无新活动";
    StringBuilder sb = new StringBuilder();
    sb.append("拉取完成：扫描 ").append(fetched).append(" 条");
    if (imported > 0) sb.append("，新增 ").append(imported).append(" 条");
    if (merged > 0) sb.append("，去重合并 ").append(merged).append(" 条");
    if (existingSkipped > 0) sb.append("，已同步跳过 ").append(existingSkipped).append(" 条");
    if (needsReview > 0) sb.append("，待核对 ").append(needsReview).append(" 条");
    if (skippedNoFit > 0) sb.append("，缺少有效原始 FIT 已跳过 ").append(skippedNoFit).append(" 条");
    return sb.toString();
  }

  private void processDelivery(
      Map<String, Object> job,
      Map<String, Object> account,
      PlatformConnector connector,
      Session session) {
    String user = CloudStore.str(job, "user_id"),
        activity = CloudStore.str(job, "activity_id"),
        accountId = CloudStore.str(account, "id");
    var payload = store.decode(CloudStore.str(job, "payload_json"));
    if (payload.get("ruleId") != null
        && job.get("confirmation_json") == null
        && store.db.queryForObject(
                "SELECT COUNT(*) FROM sync_rule WHERE id=? AND user_id=? AND"
                    + " destination_account_id=? AND enabled=TRUE",
                Integer.class,
                payload.get("ruleId"),
                user,
                accountId)
            == 0) {
      finish(job, "CANCELLED", "同步规则已关闭，任务已取消", 0);
      return;
    }
    if (store.db.queryForObject(
            "SELECT COUNT(*) FROM platform_copy WHERE user_id=? AND activity_id=? AND account_id=?",
            Integer.class,
            user,
            activity,
            accountId)
        > 0) {
      finish(job, "SUCCEEDED", "目标平台已存在该活动", 0);
      return;
    }
    String token = CloudStore.str(job, "confirmation_json");
    ConnectorResult<UploadResult> result;
    if ("UPLOAD_STARTED".equals(token)) {
      finish(job, "WAITING_USER", "上次上传结果未知，请在目标平台核对，系统不会重复上传", 0);
      return;
    }
    if (token != null) result = connector.checkUpload(session, token);
    else {
      // 在任何远端 POST 前持久化不确定标记；进程崩溃后只能核对，不得重传。
      if (store.db.update(
              "UPDATE sync_job SET confirmation_json='UPLOAD_STARTED' WHERE id=? AND lease_owner=?",
              job.get("id"),
              worker)
          == 0) return;
      byte[] bytes =
          payload.get("variantId") == null
              ? activities.file(user, activity)
              : activities.variantFile(user, activity, payload.get("variantId").toString());
      result = connector.uploadFit(session, bytes, "FitSync-" + activity + ".fit");
      if (!Set.of(
              ConnectorResult.Status.PROCESSING,
              ConnectorResult.Status.SUCCESS,
              ConnectorResult.Status.DUPLICATE)
          .contains(result.status()))
        store.db.update(
            "UPDATE sync_job SET confirmation_json=NULL WHERE id=? AND lease_owner=?",
            job.get("id"),
            worker);
    }
    if (result.status() == ConnectorResult.Status.SUCCESS
        || result.status() == ConnectorResult.Status.DUPLICATE) {
      if (result.value() == null || result.value().remoteActivityId() == null) {
        finish(job, "WAITING_USER", "平台未返回真实活动编号，请核对上传结果", 0);
        return;
      }
      activities.mapCopy(user, activity, accountId, result.value().remoteActivityId());
      finish(job, "SUCCEEDED", "活动已在目标平台确认", 0);
    } else applyFailure(job, result);
  }

  private void applyFailure(Map<String, Object> job, ConnectorResult<?> result) {
    String state =
        switch (result.status()) {
          case PROCESSING -> "CONFIRMING";
          case REQUIRES_LOGIN, REQUIRES_USER_ACTION -> "WAITING_USER";
          case RETRYABLE_FAILURE -> "RETRY";
          default -> "FAILED";
        };
    if (result.status() == ConnectorResult.Status.PROCESSING) {
      if (result.confirmationToken() == null) state = "WAITING_USER";
      else
        store.db.update(
            "UPDATE sync_job SET confirmation_json=? WHERE id=? AND lease_owner=?",
            result.confirmationToken(),
            job.get("id"),
            worker);
    }
    if (result.status() == ConnectorResult.Status.REQUIRES_LOGIN)
      store.db.update(
          "UPDATE platform_account SET status='AUTH_EXPIRED',last_error='登录已失效，请重新连接' WHERE id=?"
              + " AND user_id=?",
          job.get("account_id"),
          job.get("user_id"));
    finish(
        job,
        state,
        result.message() == null ? "平台请求失败" : result.message(),
        result.retryAfterMillis());
  }

  private void finish(Map<String, Object> job, String state, String message, long retryAfter) {
    int attempts = ((Number) job.get("attempt_count")).intValue();
    long now = System.currentTimeMillis();
    if (state.equals("RETRY")) {
      attempts++;
      if (attempts >= 8) {
        state = "WAITING_USER";
        message = "多次重试仍未完成，请检查平台连接后重试";
      }
    }
    long delay =
        state.equals("RETRY")
            ? Math.min(
                Duration.ofHours(6).toMillis(),
                Math.max(retryAfter, 30000L * (1L << Math.min(attempts, 10))))
            : Math.max(0, retryAfter);
    if (message.length() > 280) message = message.substring(0, 280);
    store.db.update(
        "UPDATE sync_job SET status=?,message=?,attempt_count=?,next_run_at=?,updated_at=? WHERE"
            + " id=? AND lease_owner=?",
        state,
        message,
        attempts,
        now + delay,
        now,
        job.get("id"),
        worker);
  }
}
