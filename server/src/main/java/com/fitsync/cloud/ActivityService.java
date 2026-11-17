package com.fitsync.cloud;

import com.fitsync.fitcore.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityService {
  private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter TITLE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final int LIST_TRACK_MAX_POINTS = 24;

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
    return importFit(user, bytes, source, accountId, remoteId, null);
  }

  @Transactional
  public synchronized Map<String, Object> importFit(
      String user,
      byte[] bytes,
      String source,
      String accountId,
      String remoteId,
      String preferredTitle) {
    // 数据库锁覆盖提交，保证多实例同一用户并发导入也不会绕过去重。
    store.db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", String.class, user);
    if (bytes == null || bytes.length == 0 || bytes.length > 25 * 1024 * 1024)
      throw ApiException.bad("FIT 文件大小必须为 1 字节至 25MB");
    String normalizedSource =
        source == null || source.isBlank() ? "LOCAL" : source.trim().toUpperCase(Locale.ROOT);
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
      return Map.of("activityId", activity, "duplicate", true, "dedupAction", "DUPLICATE_FILE");
    }
    String activityId, candidateId;
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
      var track = decodeTrackPoints(metadata.get("track"));
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
    long now = System.currentTimeMillis();
    if ("AUTO_MERGE".equals(match.action()) && candidateId != null) {
      activityId = candidateId;
    } else {
      review = "NEEDS_REVIEW".equals(match.action()) && candidateId != null;
      activityId = CloudStore.id();
      String title = resolveTitle(preferredTitle, parsed.startTimeEpochMs(), parsed.sportType());
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
          normalizedSource,
          review,
          review ? candidateId : null,
          now);
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
            + " activity_variant(id,user_id,activity_id,sha256,file_path,metadata_json,quality_score,source_platform,remote_id,file_size,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        variantId,
        user,
        activityId,
        parsed.sha256(),
        root.relativize(file).toString(),
        store.encode(parsed),
        parsed.qualityScore(),
        normalizedSource,
        remoteId,
        (long) bytes.length,
        now);
    var activity = store.owned("activity", activityId, user);
    boolean replace = activity.get("primary_variant_id") == null;
    if (!replace) {
      var oldRows =
          store.db.queryForList(
              "SELECT quality_score FROM activity_variant WHERE id=? AND user_id=?",
              activity.get("primary_variant_id"),
              user);
      int old =
          oldRows.isEmpty() || !(oldRows.getFirst().get("quality_score") instanceof Number n)
              ? Integer.MIN_VALUE
              : n.intValue();
      replace = parsed.qualityScore() > old;
    }
    String nextPrimary =
        replace ? variantId : CloudStore.str(activity, "primary_variant_id");
    Double nextDistance =
        replace
            ? firstNonNull(parsed.distanceMeters(), asDouble(activity.get("distance_meters")))
            : firstNonNull(asDouble(activity.get("distance_meters")), parsed.distanceMeters());
    Integer nextElapsed =
        replace
            ? firstNonNull(parsed.elapsedSeconds(), asInt(activity.get("elapsed_seconds")))
            : firstNonNull(asInt(activity.get("elapsed_seconds")), parsed.elapsedSeconds());
    Integer nextMoving =
        replace
            ? firstNonNull(parsed.movingSeconds(), asInt(activity.get("moving_seconds")))
            : firstNonNull(asInt(activity.get("moving_seconds")), parsed.movingSeconds());
    String existingSport = CloudStore.str(activity, "sport_type");
    String nextSport =
        (existingSport == null || "UNKNOWN".equals(existingSport))
                && parsed.sportType() != null
                && !"UNKNOWN".equals(parsed.sportType())
            ? parsed.sportType()
            : (existingSport != null ? existingSport : parsed.sportType());
    String existingTitle = CloudStore.str(activity, "title");
    String nextTitle = existingTitle;
    if (isGenericTitle(existingTitle)) {
      nextTitle = resolveTitle(preferredTitle, CloudStore.number(activity, "start_time"), nextSport);
    }
    store.db.update(
        "UPDATE activity SET"
            + " primary_variant_id=?,title=?,distance_meters=?,elapsed_seconds=?,moving_seconds=?,sport_type=?"
            + " WHERE id=? AND user_id=?",
        nextPrimary,
        nextTitle,
        nextDistance,
        nextElapsed,
        nextMoving,
        nextSport,
        activityId,
        user);
    mapCopy(user, activityId, accountId, remoteId);
    return Map.of(
        "activityId",
        activityId,
        "duplicate",
        false,
        "dedupAction",
        match.action(),
        "needsReview",
        review);
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
    return page(user, page, size, null, "ALL", null, "NEWEST");
  }

  public Map<String, Object> page(
      String user,
      int page,
      int size,
      String query,
      String filter,
      String platform,
      String sort) {
    int limit = Math.clamp(size, 1, 100);
    int offset = Math.max(0, page) * limit;
    StringBuilder where = new StringBuilder(" WHERE a.user_id=?");
    List<Object> params = new ArrayList<>();
    params.add(user);

    String normalizedFilter =
        filter == null || filter.isBlank() ? "ALL" : filter.trim().toUpperCase(Locale.ROOT);
    switch (normalizedFilter) {
      case "CYCLING" ->
          where.append(" AND a.sport_type IN ('CYCLING','INDOOR_CYCLING','MTB','GRAVEL')");
      case "RUNNING" -> where.append(" AND a.sport_type='RUNNING'");
      case "REVIEW" -> where.append(" AND a.needs_review=TRUE");
      default -> {}
    }

    String normalizedPlatform =
        platform == null ? "" : platform.trim().toUpperCase(Locale.ROOT);
    if (!normalizedPlatform.isBlank() && !"ALL".equals(normalizedPlatform)) {
      where.append(
          " AND (a.source_platform=? OR EXISTS(SELECT 1 FROM activity_variant v2 WHERE"
              + " v2.activity_id=a.id AND v2.user_id=a.user_id AND v2.source_platform=?)"
              + " OR EXISTS(SELECT 1 FROM platform_copy p2 JOIN platform_account c2 ON"
              + " c2.id=p2.account_id WHERE p2.activity_id=a.id AND p2.user_id=a.user_id AND"
              + " c2.platform=?))");
      params.add(normalizedPlatform);
      params.add(normalizedPlatform);
      params.add(normalizedPlatform);
    }

    String cleanedQuery = query == null ? "" : query.trim();
    if (cleanedQuery.length() > 80) cleanedQuery = cleanedQuery.substring(0, 80);
    if (!cleanedQuery.isBlank()) {
      String escaped =
          cleanedQuery
              .toLowerCase(Locale.ROOT)
              .replace("\\", "\\\\")
              .replace("%", "\\%")
              .replace("_", "\\_");
      where.append(" AND (LOWER(a.title) LIKE ? ESCAPE '\\'");
      params.add("%" + escaped + "%");
      if (cleanedQuery.contains("骑")) {
        where.append(" OR a.sport_type IN ('CYCLING','INDOOR_CYCLING','MTB','GRAVEL')");
      }
      if (cleanedQuery.contains("跑")) {
        where.append(" OR a.sport_type='RUNNING'");
      }
      if (cleanedQuery.contains("室内")) {
        where.append(" OR a.sport_type='INDOOR_CYCLING'");
      }
      if (cleanedQuery.contains("山地")) {
        where.append(" OR a.sport_type='MTB'");
      }
      if (cleanedQuery.contains("砾石")) {
        where.append(" OR a.sport_type='GRAVEL'");
      }
      where.append(")");
    }

    String orderBy =
        "OLDEST".equalsIgnoreCase(sort)
            ? " ORDER BY a.start_time ASC, a.id ASC"
            : " ORDER BY a.start_time DESC, a.id DESC";

    Long total =
        store.db.queryForObject(
            "SELECT COUNT(*) FROM activity a" + where, Long.class, params.toArray());

    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(limit);
    pageParams.add(offset);
    var rows =
        store.db.queryForList(
            "SELECT a.*,(SELECT COUNT(*) FROM activity_variant v WHERE v.activity_id=a.id AND"
                + " v.user_id=a.user_id) AS variant_count, pv.metadata_json AS"
                + " primary_metadata_json FROM activity a LEFT JOIN activity_variant pv ON"
                + " pv.id=a.primary_variant_id"
                + where
                + orderBy
                + " LIMIT ? OFFSET ?",
            pageParams.toArray());

    Map<String, LinkedHashSet<String>> platformsByActivity = batchPlatforms(user, rows);
    var items =
        rows.stream()
            .map(
                row -> {
                  var item = summary(row);
                  String id = CloudStore.str(row, "id");
                  var platforms = platformsByActivity.get(id);
                  item.put("platforms", platforms == null ? List.of() : List.copyOf(platforms));
                  if (row.get("primary_metadata_json") instanceof String meta && !meta.isBlank()) {
                    try {
                      var decoded = store.decode(meta);
                      var track = downsampleTrack(decodeTrackPoints(decoded.get("track")), LIST_TRACK_MAX_POINTS);
                      if (track.size() >= 2) {
                        item.put("track", track);
                      }
                    } catch (Exception ignored) {
                    }
                  }
                  return item;
                })
            .toList();

    return Map.of("items", items, "total", total == null ? 0L : total);
  }

  private Map<String, LinkedHashSet<String>> batchPlatforms(
      String user, List<Map<String, Object>> rows) {
    Map<String, LinkedHashSet<String>> result = new LinkedHashMap<>();
    if (rows.isEmpty()) return result;
    List<String> ids = new ArrayList<>(rows.size());
    for (var row : rows) {
      String id = CloudStore.str(row, "id");
      if (id == null) continue;
      ids.add(id);
      var set = new LinkedHashSet<String>();
      String src = CloudStore.str(row, "source_platform");
      if (src != null && !src.isBlank()) set.add(src);
      result.put(id, set);
    }
    if (ids.isEmpty()) return result;
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    List<Object> args = new ArrayList<>(ids.size() + 1);
    args.add(user);
    args.addAll(ids);
    for (var v :
        store.db.queryForList(
            "SELECT activity_id,source_platform FROM activity_variant WHERE user_id=? AND"
                + " activity_id IN ("
                + placeholders
                + ") ORDER BY created_at ASC",
            args.toArray())) {
      String actId = CloudStore.str(v, "activity_id");
      String sp = CloudStore.str(v, "source_platform");
      if (actId != null && sp != null && !sp.isBlank() && result.containsKey(actId)) {
        result.get(actId).add(sp);
      }
    }
    for (var c :
        store.db.queryForList(
            "SELECT p.activity_id,a.platform FROM platform_copy p JOIN platform_account a ON"
                + " a.id=p.account_id WHERE p.user_id=? AND p.activity_id IN ("
                + placeholders
                + ")",
            args.toArray())) {
      String actId = CloudStore.str(c, "activity_id");
      String pl = CloudStore.str(c, "platform");
      if (actId != null && pl != null && !pl.isBlank() && result.containsKey(actId)) {
        result.get(actId).add(pl);
      }
    }
    return result;
  }

  public Map<String, Object> summary(Map<String, Object> row) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", row.get("id"));
    result.put("title", row.get("title"));
    result.put("startTimeEpochMs", row.get("start_time"));
    result.put("distanceMeters", row.get("distance_meters"));
    result.put("elapsedSeconds", row.get("elapsed_seconds"));
    result.put("movingSeconds", row.get("moving_seconds"));
    result.put("sportType", row.get("sport_type"));
    result.put("sourcePlatform", row.get("source_platform"));
    result.put("variantCount", row.getOrDefault("variant_count", 1));
    result.put("needsReview", row.get("needs_review"));
    return result;
  }

  public Map<String, Object> detail(String user, String id) {
    var row = store.owned("activity", id, user);
    row.put(
        "variant_count",
        store.db.queryForObject(
            "SELECT COUNT(*) FROM activity_variant WHERE activity_id=? AND user_id=?",
            Long.class,
            id,
            user));
    var result = summary(row);
    String primaryId = CloudStore.str(row, "primary_variant_id");
    var variant =
        store.one(
            "SELECT metadata_json FROM activity_variant WHERE id=? AND user_id=?",
            primaryId,
            user);
    result.putAll(store.decode(CloudStore.str(variant, "metadata_json")));
    result.put("id", id);
    result.put("title", row.get("title"));
    var copies =
        store.db.queryForList(
            "SELECT p.remote_id AS \"remoteActivityId\",a.platform,a.id AS \"accountId\" FROM"
                + " platform_copy p JOIN platform_account a ON a.id=p.account_id WHERE"
                + " p.user_id=? AND p.activity_id=?",
            user,
            id);
    result.put("copies", copies);

    var variants =
        store.db
            .queryForList(
                "SELECT id,source_platform,remote_id,file_size,quality_score,sha256,created_at"
                    + " FROM activity_variant WHERE user_id=? AND activity_id=? ORDER BY"
                    + " quality_score DESC, created_at ASC",
                user,
                id)
            .stream()
            .map(
                v -> {
                  Map<String, Object> item = new LinkedHashMap<>();
                  String vid = CloudStore.str(v, "id");
                  item.put("id", vid);
                  item.put("sourcePlatform", CloudStore.str(v, "source_platform"));
                  item.put("remoteActivityId", CloudStore.str(v, "remote_id"));
                  item.put("fileSize", v.get("file_size"));
                  item.put("qualityScore", v.get("quality_score"));
                  item.put("sha256", CloudStore.str(v, "sha256"));
                  item.put("isPrimary", Objects.equals(vid, primaryId));
                  item.put("createdAtEpochMs", v.get("created_at"));
                  return item;
                })
            .toList();
    result.put("variants", variants);

    var platforms = new LinkedHashSet<String>();
    String src = CloudStore.str(row, "source_platform");
    if (src != null && !src.isBlank()) platforms.add(src);
    for (var v : variants) {
      Object sp = v.get("sourcePlatform");
      if (sp != null && !sp.toString().isBlank()) platforms.add(sp.toString());
    }
    for (var c : copies) {
      Object pl = c.get("platform");
      if (pl != null && !pl.toString().isBlank()) platforms.add(pl.toString());
    }
    result.put("platforms", List.copyOf(platforms));

    if (row.get("review_candidate_id") != null) {
      String candId = CloudStore.str(row, "review_candidate_id");
      result.put("reviewCandidateId", candId);
      var candRows =
          store.db.queryForList(
              "SELECT * FROM activity WHERE id=? AND user_id=?", candId, user);
      if (!candRows.isEmpty()) {
        result.put("reviewCandidate", summary(candRows.getFirst()));
      }
    }
    return result;
  }

  @Transactional
  public Map<String, Object> keepSeparate(String user, String id, String decision) {
    return review(user, id, decision);
  }

  @Transactional
  public Map<String, Object> review(String user, String id, String decision) {
    if ("KEEP_SEPARATE".equals(decision)) {
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
    if ("MERGE_INTO_CANDIDATE".equals(decision)) {
      return mergeIntoCandidate(user, id);
    }
    throw ApiException.bad("请选择保持分离或合并到候选活动");
  }

  private Map<String, Object> mergeIntoCandidate(String user, String sourceId) {
    store.db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", String.class, user);
    var source = store.owned("activity", sourceId, user);
    if (!Boolean.TRUE.equals(source.get("needs_review"))) {
      throw ApiException.bad("当前活动无需核对合并");
    }
    String targetId = CloudStore.str(source, "review_candidate_id");
    if (targetId == null || targetId.equals(sourceId)) {
      throw ApiException.bad("没有可合并的候选活动");
    }
    var targetRows =
        store.db.queryForList(
            "SELECT * FROM activity WHERE id=? AND user_id=?", targetId, user);
    if (targetRows.isEmpty()) {
      throw ApiException.bad("候选活动已不存在，请改为保持分离");
    }
    var target = targetRows.getFirst();

    int movedVariants =
        store.db.update(
            "UPDATE activity_variant SET activity_id=? WHERE activity_id=? AND user_id=?",
            targetId,
            sourceId,
            user);

    var targetAccountIds =
        new HashSet<>(
            store.db.queryForList(
                    "SELECT account_id FROM platform_copy WHERE activity_id=? AND user_id=?",
                    targetId,
                    user)
                .stream()
                .map(r -> CloudStore.str(r, "account_id"))
                .toList());
    for (var copy :
        store.db.queryForList(
            "SELECT id,account_id FROM platform_copy WHERE activity_id=? AND user_id=?",
            sourceId,
            user)) {
      String copyId = CloudStore.str(copy, "id");
      String accountId = CloudStore.str(copy, "account_id");
      if (targetAccountIds.contains(accountId)) {
        store.db.update("DELETE FROM platform_copy WHERE id=? AND user_id=?", copyId, user);
      } else {
        store.db.update(
            "UPDATE platform_copy SET activity_id=? WHERE id=? AND user_id=?",
            targetId,
            copyId,
            user);
        targetAccountIds.add(accountId);
      }
    }

    var targetJobKeys = new HashSet<String>();
    for (var tj :
        store.db.queryForList(
            "SELECT account_id,kind FROM sync_job WHERE activity_id=? AND user_id=?",
            targetId,
            user)) {
      targetJobKeys.add(CloudStore.str(tj, "account_id") + ":" + CloudStore.str(tj, "kind"));
    }
    for (var sj :
        store.db.queryForList(
            "SELECT id,account_id,kind FROM sync_job WHERE activity_id=? AND user_id=?",
            sourceId,
            user)) {
      String jobId = CloudStore.str(sj, "id");
      String accountId = CloudStore.str(sj, "account_id");
      String kind = CloudStore.str(sj, "kind");
      String key = accountId + ":" + kind;
      if (targetJobKeys.contains(key)
          || ("DELIVERY".equals(kind) && targetAccountIds.contains(accountId))) {
        store.db.update("DELETE FROM sync_job WHERE id=? AND user_id=?", jobId, user);
      } else {
        store.db.update(
            "UPDATE sync_job SET activity_id=? WHERE id=? AND user_id=?",
            targetId,
            jobId,
            user);
        targetJobKeys.add(key);
      }
    }

    store.db.update(
        "UPDATE activity SET review_candidate_id=? WHERE review_candidate_id=? AND user_id=? AND"
            + " id<>?",
        targetId,
        sourceId,
        user,
        targetId);
    store.db.update(
        "UPDATE activity SET needs_review=FALSE,review_candidate_id=NULL WHERE id=? AND"
            + " review_candidate_id=? AND user_id=?",
        targetId,
        sourceId,
        user);

    var bestRows =
        store.db.queryForList(
            "SELECT id FROM activity_variant WHERE activity_id=? AND user_id=? ORDER BY"
                + " quality_score DESC, created_at ASC LIMIT 1",
            targetId,
            user);
    String bestPrimary =
        bestRows.isEmpty()
            ? CloudStore.str(target, "primary_variant_id")
            : CloudStore.str(bestRows.getFirst(), "id");
    Double distance =
        firstNonNull(asDouble(target.get("distance_meters")), asDouble(source.get("distance_meters")));
    Integer elapsed =
        firstNonNull(asInt(target.get("elapsed_seconds")), asInt(source.get("elapsed_seconds")));
    Integer moving =
        firstNonNull(asInt(target.get("moving_seconds")), asInt(source.get("moving_seconds")));
    String targetSport = CloudStore.str(target, "sport_type");
    String sourceSport = CloudStore.str(source, "sport_type");
    String sport =
        (targetSport == null || "UNKNOWN".equals(targetSport)) && sourceSport != null
            ? sourceSport
            : targetSport;
    String targetTitle = CloudStore.str(target, "title");
    String sourceTitle = CloudStore.str(source, "title");
    String nextTitle =
        !isGenericTitle(targetTitle)
            ? targetTitle
            : (!isGenericTitle(sourceTitle)
                ? sourceTitle
                : displayTitle(CloudStore.number(target, "start_time"), sport));

    store.db.update(
        "UPDATE activity SET"
            + " primary_variant_id=?,title=?,distance_meters=?,elapsed_seconds=?,moving_seconds=?,sport_type=?"
            + " WHERE id=? AND user_id=?",
        bestPrimary,
        nextTitle,
        distance,
        elapsed,
        moving,
        sport,
        targetId,
        user);
    store.db.update("DELETE FROM activity WHERE id=? AND user_id=?", sourceId, user);

    return Map.of(
        "activityId",
        targetId,
        "mergedFromId",
        sourceId,
        "needsReview",
        false,
        "alreadyReviewed",
        false,
        "merged",
        true,
        "movedVariants",
        movedVariants);
  }

  @Transactional
  public void delete(String user, String id) {
    store.db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", String.class, user);
    store.owned("activity", id, user);
    long now = System.currentTimeMillis();
    if (store.db.queryForObject(
            "SELECT COUNT(*) FROM sync_job WHERE activity_id=? AND user_id=? AND status='RUNNING'"
                + " AND lease_until>?",
            Integer.class,
            id,
            user,
            now)
        > 0) {
      throw ApiException.bad("该活动正在同步处理中，请稍后再试");
    }
    var filePaths =
        store.db.queryForList(
                "SELECT file_path FROM activity_variant WHERE activity_id=? AND user_id=?",
                id,
                user)
            .stream()
            .map(r -> CloudStore.str(r, "file_path"))
            .filter(Objects::nonNull)
            .distinct()
            .toList();

    store.db.update(
        "UPDATE activity SET review_candidate_id=NULL WHERE review_candidate_id=? AND user_id=?",
        id,
        user);
    store.db.update("DELETE FROM sync_job WHERE activity_id=? AND user_id=?", id, user);
    store.db.update("DELETE FROM platform_copy WHERE activity_id=? AND user_id=?", id, user);
    store.db.update("DELETE FROM activity_variant WHERE activity_id=? AND user_id=?", id, user);
    store.db.update("DELETE FROM activity WHERE id=? AND user_id=?", id, user);

    for (String relPath : filePaths) {
      if (store.db.queryForObject(
              "SELECT COUNT(*) FROM activity_variant WHERE user_id=? AND file_path=?",
              Integer.class,
              user,
              relPath)
          == 0) {
        try {
          Path path = root.resolve(relPath).normalize();
          if (path.startsWith(root.resolve(user))) {
            Files.deleteIfExists(path);
          }
        } catch (Exception ignored) {
        }
      }
    }
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

  private static List<TrackPoint> decodeTrackPoints(Object raw) {
    var track = new ArrayList<TrackPoint>();
    if (raw instanceof List<?> points) {
      for (Object point : points) {
        if (point instanceof Map<?, ?> p
            && p.get("lat") instanceof Number lat
            && p.get("lon") instanceof Number lon) {
          track.add(new TrackPoint(lat.doubleValue(), lon.doubleValue()));
        }
      }
    }
    return track;
  }

  private static List<Map<String, Double>> downsampleTrack(List<TrackPoint> points, int maxPoints) {
    if (points.size() < 2) return List.of();
    int step = Math.max(1, (int) Math.ceil((double) points.size() / maxPoints));
    var out = new ArrayList<Map<String, Double>>(Math.min(points.size(), maxPoints) + 1);
    for (int i = 0; i < points.size(); i++) {
      if (i % step == 0 || i == points.size() - 1) {
        TrackPoint pt = points.get(i);
        out.add(Map.of("lat", pt.lat(), "lon", pt.lon()));
      }
    }
    return out;
  }

  static String resolveTitle(String preferredTitle, long startTimeEpochMs, String sportType) {
    if (preferredTitle != null) {
      String cleaned = preferredTitle.replace('\r', ' ').replace('\n', ' ').trim();
      if (!cleaned.isBlank()) {
        return cleaned.length() > 160 ? cleaned.substring(0, 160) : cleaned;
      }
    }
    return displayTitle(startTimeEpochMs, sportType);
  }

  static String displayTitle(long startTimeEpochMs, String sportType) {
    ZonedDateTime dt = Instant.ofEpochMilli(startTimeEpochMs).atZone(CHINA);
    int hour = dt.getHour();
    String period =
        hour >= 5 && hour <= 11
            ? "上午"
            : (hour >= 12 && hour <= 17 ? "下午" : (hour >= 18 && hour <= 19 ? "傍晚" : "晚上"));
    return TITLE_DATE.format(dt) + " " + period + " " + sportLabel(sportType);
  }

  private static boolean isGenericTitle(String title) {
    if (title == null || title.isBlank()) return true;
    String t = title.trim();
    return "导入的骑行活动".equals(t) || t.endsWith(" 骑行活动");
  }

  private static String sportLabel(String sportType) {
    if (sportType == null) return "骑行";
    return switch (sportType) {
      case "CYCLING" -> "骑行";
      case "INDOOR_CYCLING" -> "室内骑行";
      case "MTB" -> "山地骑行";
      case "GRAVEL" -> "砾石骑行";
      case "RUNNING" -> "跑步";
      case "OTHER" -> "其他运动";
      default -> "骑行";
    };
  }

  private static Double asDouble(Object value) {
    return value instanceof Number n ? n.doubleValue() : null;
  }

  private static Integer asInt(Object value) {
    return value instanceof Number n ? n.intValue() : null;
  }

  private static <T> T firstNonNull(T first, T second) {
    return first != null ? first : second;
  }
}
