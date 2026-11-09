package com.fitsync.cloud;

import com.fitsync.fitcore.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityService {
  private final CloudStore store;
  private final Path root;
  private final FitParser parser = new FitParser();

  public ActivityService(CloudStore store, @Value("${fitsync.storage-dir}") String root)
      throws Exception {
    this.store = store;
    this.root = Path.of(root).toAbsolutePath().normalize();
    Files.createDirectories(this.root);
  }

  @Transactional
  public synchronized Map<String, Object> importFit(
      String user, byte[] bytes, String source, String accountId, String remoteId) {
    // 数据库锁覆盖提交，保证多实例同一用户并发导入也不会绕过去重。
    store.db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", String.class, user);
    if (bytes.length == 0 || bytes.length > 25 * 1024 * 1024)
      throw ApiException.bad("FIT 文件大小必须为 1 字节至 25MB");
    ParsedFit parsed;
    try {
      bytes = FitParser.normalize(bytes);
      parsed = parser.parse(bytes);
    } catch (Exception e) {
      throw ApiException.bad("FIT 文件校验失败，请选择完整的原始 FIT 文件");
    }
    var existing =
        store.db.queryForList(
            "SELECT activity_id FROM activity_variant WHERE user_id=? AND sha256=?",
            user,
            parsed.sha256());
    if (!existing.isEmpty()) {
      String activity = CloudStore.str(existing.getFirst(), "activity_id");
      mapCopy(user, activity, accountId, remoteId);
      return Map.of("activityId", activity, "duplicate", true);
    }
    String activityId = null, candidateId = null;
    boolean review = false;
    var fingerprints = new ArrayList<DedupCandidate>();
    var candidateIds = new HashMap<Long, String>();
    var candidates =
        store.db.queryForList(
            "SELECT a.*,v.metadata_json FROM activity a JOIN activity_variant v ON"
                + " v.id=a.primary_variant_id WHERE a.user_id=? AND a.start_time BETWEEN ? AND ?",
            user,
            parsed.startTimeEpochMs() - 600000,
            parsed.startTimeEpochMs() + 600000);
    for (var candidate : candidates) {
      var metadata = store.decode(CloudStore.str(candidate, "metadata_json"));
      var track = new ArrayList<TrackPoint>();
      if (metadata.get("track") instanceof List<?> points)
        for (Object point : points) {
          var p = (Map<?, ?>) point;
          track.add(
              new TrackPoint(
                  ((Number) p.get("lat")).doubleValue(), ((Number) p.get("lon")).doubleValue()));
        }
      long localId = fingerprints.size() + 1L;
      candidateIds.put(localId, CloudStore.str(candidate, "id"));
      fingerprints.add(
          new DedupCandidate(
              localId,
              CloudStore.str(candidate, "sport_type"),
              CloudStore.number(candidate, "start_time"),
              candidate.get("elapsed_seconds") instanceof Number n ? n.intValue() : null,
              candidate.get("moving_seconds") instanceof Number n ? n.intValue() : null,
              candidate.get("distance_meters") instanceof Number n ? n.doubleValue() : null,
              track));
    }
    var match = new FitDeduplicator().decide(bytes, fingerprints);
    candidateId =
        match.matchedCanonicalId() == null ? null : candidateIds.get(match.matchedCanonicalId());
    if (match.action().equals("AUTO_MERGE")) activityId = candidateId;
    else {
      review = match.action().equals("NEEDS_REVIEW");
      activityId = CloudStore.id();
      String title = source.equals("LOCAL") ? "导入的骑行活动" : source + " 骑行活动";
      store.db.update(
          "INSERT INTO"
              + " activity(id,user_id,title,start_time,distance_meters,elapsed_seconds,moving_seconds,sport_type,source_platform,needs_review,review_candidate_id,created_at)"
              + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
          activityId,
          user,
          title,
          parsed.startTimeEpochMs(),
          parsed.distanceMeters(),
          parsed.elapsedSeconds(),
          parsed.movingSeconds(),
          parsed.sportType(),
          source,
          review,
          review ? candidateId : null,
          System.currentTimeMillis());
    }
    String variantId = CloudStore.id();
    Path directory = root.resolve(user);
    Path file = directory.resolve(parsed.sha256() + ".fit");
    try {
      Files.createDirectories(directory);
      if (!Files.exists(file)) Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
    } catch (Exception e) {
      throw new IllegalStateException("原始 FIT 保存失败", e);
    }
    store.db.update(
        "INSERT INTO"
            + " activity_variant(id,user_id,activity_id,sha256,file_path,metadata_json,quality_score)"
            + " VALUES(?,?,?,?,?,?,?)",
        variantId,
        user,
        activityId,
        parsed.sha256(),
        root.relativize(file).toString(),
        store.encode(parsed),
        parsed.qualityScore());
    var activity = store.owned("activity", activityId, user);
    boolean replace = activity.get("primary_variant_id") == null;
    if (!replace) {
      int old =
          store.db.queryForObject(
              "SELECT quality_score FROM activity_variant WHERE id=? AND user_id=?",
              Integer.class,
              activity.get("primary_variant_id"),
              user);
      replace = parsed.qualityScore() > old;
    }
    if (replace)
      store.db.update(
          "UPDATE activity SET"
              + " primary_variant_id=?,distance_meters=?,elapsed_seconds=?,moving_seconds=? WHERE"
              + " id=? AND user_id=?",
          variantId,
          parsed.distanceMeters(),
          parsed.elapsedSeconds(),
          parsed.movingSeconds(),
          activityId,
          user);
    mapCopy(user, activityId, accountId, remoteId);
    return Map.of("activityId", activityId, "duplicate", false);
  }

  public void mapCopy(String user, String activity, String account, String remote) {
    if (account == null || remote == null) return;
    store.owned("platform_account", account, user);
    store.owned("activity", activity, user);
    var rows =
        store.db.queryForList(
            "SELECT id,activity_id FROM platform_copy WHERE user_id=? AND account_id=? AND"
                + " remote_id=?",
            user,
            account,
            remote);
    if (!rows.isEmpty()) return;
    if (store.db.queryForObject(
            "SELECT COUNT(*) FROM platform_copy WHERE user_id=? AND activity_id=? AND account_id=?",
            Integer.class,
            user,
            activity,
            account)
        > 0) return;
    store.db.update(
        "INSERT INTO platform_copy(id,user_id,activity_id,account_id,remote_id) VALUES(?,?,?,?,?)",
        CloudStore.id(),
        user,
        activity,
        account,
        remote);
  }

  public Map<String, Object> page(String user, int page, int size) {
    int limit = Math.clamp(size, 1, 100);
    int offset = Math.max(0, page) * limit;
    var rows =
        store.db.queryForList(
            "SELECT a.*,(SELECT COUNT(*) FROM activity_variant v WHERE v.activity_id=a.id AND"
                + " v.user_id=a.user_id) AS variant_count FROM activity a WHERE a.user_id=? ORDER"
                + " BY a.start_time DESC LIMIT ? OFFSET ?",
            user,
            limit,
            offset);
    return Map.of(
        "items",
        rows.stream().map(this::summary).toList(),
        "total",
        store.db.queryForObject("SELECT COUNT(*) FROM activity WHERE user_id=?", Long.class, user));
  }

  public Map<String, Object> summary(Map<String, Object> row) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", row.get("id"));
    result.put("title", row.get("title"));
    result.put("startTimeEpochMs", row.get("start_time"));
    result.put("distanceMeters", row.get("distance_meters"));
    result.put("elapsedSeconds", row.get("elapsed_seconds"));
    result.put("sportType", row.get("sport_type"));
    result.put("sourcePlatform", row.get("source_platform"));
    result.put("variantCount", row.getOrDefault("variant_count", 1));
    result.put("needsReview", row.get("needs_review"));
    return result;
  }

  public Map<String, Object> detail(String user, String id) {
    var row = store.owned("activity", id, user);
    row.put("variant_count", store.db.queryForObject(
        "SELECT COUNT(*) FROM activity_variant WHERE activity_id=? AND user_id=?", Long.class, id, user));
    var result = summary(row);
    var variant =
        store.one(
            "SELECT metadata_json FROM activity_variant WHERE id=? AND user_id=?",
            row.get("primary_variant_id"),
            user);
    result.putAll(store.decode(CloudStore.str(variant, "metadata_json")));
    result.put("id", id);
    result.put("title", row.get("title"));
    result.put(
        "copies",
        store.db.queryForList(
            "SELECT p.remote_id AS \"remoteActivityId\",a.platform FROM platform_copy p JOIN"
                + " platform_account a ON a.id=p.account_id WHERE p.user_id=? AND p.activity_id=?",
            user,
            id));
    if (row.get("review_candidate_id") != null)
      result.put("reviewCandidateId", row.get("review_candidate_id"));
    return result;
  }

  @Transactional
  public Map<String, Object> keepSeparate(String user, String id, String decision) {
    if (!"KEEP_SEPARATE".equals(decision)) throw ApiException.bad("请选择保持分离");
    store.db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", String.class, user);
    var activity = store.owned("activity", id, user);
    boolean reviewed = !Boolean.TRUE.equals(activity.get("needs_review"));
    if (!reviewed)
      store.db.update(
          "UPDATE activity SET needs_review=FALSE,review_candidate_id=NULL WHERE id=? AND"
              + " user_id=?",
          id,
          user);
    return Map.of("activityId", id, "needsReview", false, "alreadyReviewed", reviewed);
  }

  public byte[] file(String user, String id) {
    var activity = store.owned("activity", id, user);
    return variantFile(user, id, CloudStore.str(activity, "primary_variant_id"));
  }

  public byte[] variantFile(String user, String activity, String variantId) {
    store.owned("activity", activity, user);
    var variant =
        store.one(
            "SELECT file_path FROM activity_variant WHERE id=? AND user_id=? AND activity_id=?",
            variantId,
            user,
            activity);
    Path path = root.resolve(CloudStore.str(variant, "file_path")).normalize();
    if (!path.startsWith(root.resolve(user))) throw ApiException.missing();
    try {
      return Files.readAllBytes(path);
    } catch (Exception e) {
      throw ApiException.missing();
    }
  }
}
