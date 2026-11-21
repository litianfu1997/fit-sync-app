package com.fitsync.cloud;

import java.util.*;
import org.springframework.stereotype.Service;

/** 首页概览聚合；数据访问全部留在服务层，控制层只负责取当前用户并转发。 */
@Service
public class DashboardService {
  private final CloudStore store;

  public DashboardService(CloudStore store) {
    this.store = store;
  }

  public Map<String, Object> summary(String user) {
    // 一条聚合语句同时取活动数与累计距离，避免控制层拼装多次查询。
    var totals =
        store.one(
            "SELECT COUNT(*) AS activity_count,COALESCE(SUM(distance_meters),0) AS"
                + " total_distance FROM activity WHERE user_id=?",
            user);
    var result = new LinkedHashMap<String, Object>();
    result.put("activityCount", totals.get("activity_count"));
    result.put("totalDistanceMeters", totals.get("total_distance"));
    result.put("pendingJobs", pendingJobs(user));
    result.put("connectedAccounts", connectedAccounts(user));
    result.put("lastSyncAt", lastSyncAt(user));
    return result;
  }

  private long pendingJobs(String user) {
    return count(
        "SELECT COUNT(*) FROM sync_job WHERE user_id=? AND status IN"
            + " ('PENDING','RUNNING','RETRY','CONFIRMING')",
        user);
  }

  private long connectedAccounts(String user) {
    return count(
        "SELECT COUNT(*) FROM platform_account WHERE user_id=? AND status='CONNECTED'", user);
  }

  /** 从未同步过时返回 null，由 Jackson 的 non_null 策略省略该字段。 */
  private Long lastSyncAt(String user) {
    return store.db.queryForObject(
        "SELECT MAX(last_sync_at) FROM platform_account WHERE user_id=?", Long.class, user);
  }

  private long count(String sql, Object... args) {
    Long value = store.db.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }
}
