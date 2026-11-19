package com.fitsync.cloud;

import com.fitsync.cloud.connector.RemoteActivity;
import java.util.*;

/** 统一活动类型分类、展开、规则序列化与远端平台预过滤逻辑。 */
final class SportTypes {
  static final List<String> DEFAULT_CATEGORIES = List.of("CYCLING", "RUNNING", "OTHER");
  private static final Set<String> CYCLING_TYPES =
      Set.of("CYCLING", "INDOOR_CYCLING", "MTB", "GRAVEL");
  private static final Set<String> OTHER_TYPES = Set.of("OTHER", "UNKNOWN");
  private static final List<String> CANONICAL_ORDER =
      List.of("CYCLING", "INDOOR_CYCLING", "MTB", "GRAVEL", "RUNNING", "OTHER", "UNKNOWN");
  private static final Set<String> SUPPORTED_TOKENS = Set.copyOf(CANONICAL_ORDER);

  private static final Set<String> OTHER_REMOTE_KEYS =
      Set.of(
          "lap_swimming",
          "open_water_swimming",
          "swimming",
          "swim",
          "walking",
          "walk",
          "hiking",
          "hike",
          "strength_training",
          "weighttraining",
          "yoga",
          "pilates",
          "cardio",
          "indoor_cardio",
          "elliptical",
          "stair_climbing",
          "stairstepper",
          "rowing",
          "indoor_rowing",
          "fitness_equipment",
          "hiit",
          "workout",
          "tennis",
          "badminton",
          "basketball",
          "soccer",
          "golf",
          "alpine_skiing",
          "alpineski",
          "snowboarding",
          "snowboard",
          "cross_fit",
          "rock_climbing",
          "kayaking",
          "stand_up_paddleboarding",
          "standuppaddleboarding",
          "floor_climbing",
          "breathwork",
          "meditation");

  private SportTypes() {}

  /** 校验并规范化前端/接口传入的活动类型列表；null 表示不限制（全部类型）。 */
  static List<String> normalizeSelection(List<String> raw) {
    if (raw == null) return null;
    if (raw.isEmpty()) throw ApiException.bad("请至少选择一种活动类型");
    LinkedHashSet<String> cleaned = new LinkedHashSet<>();
    for (String item : raw) {
      if (item == null || item.isBlank()) continue;
      String token = item.trim().toUpperCase(Locale.ROOT);
      if (!SUPPORTED_TOKENS.contains(token)) {
        throw ApiException.bad("不支持的活动类型：" + item);
      }
      cleaned.add(token);
    }
    if (cleaned.isEmpty()) throw ApiException.bad("请至少选择一种活动类型");
    List<String> ordered = new ArrayList<>(cleaned.size());
    for (String candidate : CANONICAL_ORDER) {
      if (cleaned.contains(candidate)) ordered.add(candidate);
    }
    return List.copyOf(ordered);
  }

  /** 将选择的分类/类型展开为底层 canonical sport_type 集合；null 表示全部允许。 */
  static Set<String> expandAllowed(Collection<String> selected) {
    if (selected == null) return null;
    LinkedHashSet<String> expanded = new LinkedHashSet<>();
    for (String item : selected) {
      if (item == null || item.isBlank()) continue;
      String token = item.trim().toUpperCase(Locale.ROOT);
      switch (token) {
        case "CYCLING" -> expanded.addAll(CYCLING_TYPES);
        case "OTHER" -> expanded.addAll(OTHER_TYPES);
        default -> expanded.add(token);
      }
    }
    return expanded.isEmpty() ? null : Set.copyOf(expanded);
  }

  static boolean matchesActivity(String sportType, Set<String> expandedAllowed) {
    if (expandedAllowed == null) return true;
    String normalized =
        sportType == null || sportType.isBlank()
            ? "UNKNOWN"
            : sportType.trim().toUpperCase(Locale.ROOT);
    return expandedAllowed.contains(normalized);
  }

  /**
   * 仅对具备清晰英文运动键值的平台（如 Garmin、Strava）做拉取前预过滤；
   * COROS / iGPSPORT 的数值枚举或未知键值返回 null，留待 FIT 解析后权威判定。
   */
  static boolean matchesRemote(
      String platform, RemoteActivity remote, Set<String> expandedAllowed) {
    if (expandedAllowed == null || remote == null) return true;
    String inferred = inferRemoteSportType(platform, remote);
    return inferred == null || expandedAllowed.contains(inferred);
  }

  static String inferRemoteSportType(String platform, RemoteActivity remote) {
    if (remote == null) return null;
    String raw = remote.metadata().get("sport_type");
    if (raw == null || raw.isBlank()) return null;
    String key = raw.trim().toLowerCase(Locale.ROOT);
    // COROS 等平台的纯数字 sportType 不做硬编码猜测，交由 FitParser 解析真实 FIT 头。
    boolean digitsOnly = true;
    for (int i = 0; i < key.length(); i++) {
      if (!Character.isDigit(key.charAt(i))) {
        digitsOnly = false;
        break;
      }
    }
    if (digitsOnly) return null;

    if ("indoor_cycling".equals(key) || "virtual_ride".equals(key) || "virtualride".equals(key)) {
      return "INDOOR_CYCLING";
    }
    if ("mountain_biking".equals(key)
        || "mountainbikeride".equals(key)
        || "e_bike_mountain".equals(key)
        || "emountainbikeride".equals(key)) {
      return "MTB";
    }
    if ("gravel_cycling".equals(key) || "gravelride".equals(key)) {
      return "GRAVEL";
    }
    if ("cycling".equals(key)
        || "road_biking".equals(key)
        || "cyclocross".equals(key)
        || "bmx".equals(key)
        || "recumbent_cycling".equals(key)
        || "handcycling".equals(key)
        || "handcycle".equals(key)
        || "velomobile".equals(key)
        || "e_bike_fitness".equals(key)
        || "track_cycling".equals(key)
        || "ride".equals(key)
        || "ebikeride".equals(key)
        || key.contains("cycling")
        || key.contains("biking")
        || (key.endsWith("ride") && !key.contains("horse"))) {
      return "CYCLING";
    }
    if ("running".equals(key)
        || "run".equals(key)
        || "trailrun".equals(key)
        || "virtualrun".equals(key)
        || "ultra_run".equals(key)
        || "obstacle_run".equals(key)
        || key.endsWith("_running")
        || key.endsWith("_run")) {
      return "RUNNING";
    }
    if (OTHER_REMOTE_KEYS.contains(key)) {
      return "OTHER";
    }
    return null;
  }

  static String encodeForRule(List<String> normalized) {
    if (normalized == null) return null;
    Set<String> expanded = expandAllowed(normalized);
    if (expanded != null
        && expanded.containsAll(CYCLING_TYPES)
        && expanded.contains("RUNNING")
        && expanded.containsAll(OTHER_TYPES)) {
      return null;
    }
    return String.join(",", normalized);
  }

  static List<String> decodeForRule(String stored) {
    if (stored == null || stored.isBlank()) return DEFAULT_CATEGORIES;
    List<String> list = new ArrayList<>();
    for (String part : stored.split(",")) {
      String token = part.trim().toUpperCase(Locale.ROOT);
      if (SUPPORTED_TOKENS.contains(token) && !list.contains(token)) {
        list.add(token);
      }
    }
    return list.isEmpty() ? DEFAULT_CATEGORIES : List.copyOf(list);
  }
}
