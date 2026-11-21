package com.fitsync.cloud;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * OAuth 授权回调与返回地址校验工具。
 *
 * <p>同时约束 Web 同源 / CORS 白名单 HTTPS 返回地址，以及 Android 云端壳的固定自定义回调
 * {@code fitsync-cloud://oauth}，防止开放重定向或伪造回调路径。
 */
final class OAuthReturnUrls {
  static final String ANDROID_APP_SCHEME = "fitsync-cloud";
  static final String ANDROID_APP_HOST = "oauth";

  private OAuthReturnUrls() {}

  static Set<String> parseAllowedOrigins(String corsOrigins) {
    Set<String> origins = new LinkedHashSet<>();
    if (corsOrigins != null) {
      for (String item : corsOrigins.split(",")) {
        String s = item.strip();
        if (!s.isEmpty()) origins.add(s.toLowerCase(Locale.ROOT));
      }
    }
    return Set.copyOf(origins);
  }

  static String defaultCallbackFromRequest(HttpServletRequest request, String callbackPath) {
    String scheme = request.getScheme();
    String host = request.getServerName();
    int port = request.getServerPort();
    boolean standardPort =
        ("http".equalsIgnoreCase(scheme) && port == 80)
            || ("https".equalsIgnoreCase(scheme) && port == 443);
    return scheme + "://" + host + (standardPort ? "" : ":" + port) + callbackPath;
  }

  static String validateReturnUrl(
      String rawReturnUrl, String redirectOrigin, Set<String> allowedOrigins) {
    if (rawReturnUrl == null || rawReturnUrl.isBlank()) return null;
    String trimmed = rawReturnUrl.strip();
    if (trimmed.indexOf('\r') >= 0 || trimmed.indexOf('\n') >= 0) {
      throw ApiException.bad("返回地址格式无效");
    }
    URI candidate;
    try {
      candidate = URI.create(trimmed);
    } catch (IllegalArgumentException e) {
      throw ApiException.bad("返回地址格式无效");
    }
    if (ANDROID_APP_SCHEME.equalsIgnoreCase(candidate.getScheme())) {
      if (!ANDROID_APP_HOST.equalsIgnoreCase(candidate.getHost())
          || candidate.getPort() != -1
          || candidate.getUserInfo() != null
          || candidate.getRawFragment() != null
          || (candidate.getPath() != null
              && !candidate.getPath().isEmpty()
              && !"/".equals(candidate.getPath()))) {
        throw ApiException.bad("应用回跳地址不在允许的白名单内");
      }
      String query = candidate.getRawQuery();
      return ANDROID_APP_SCHEME + "://" + ANDROID_APP_HOST + (query == null ? "" : "?" + query);
    }

    URI uri = parseHttpUri(trimmed, "返回地址格式无效");
    if (!"https".equalsIgnoreCase(uri.getScheme()) && !isLoopback(uri.getHost())) {
      throw ApiException.bad("返回地址必须使用 HTTPS 或本地开发地址");
    }
    String origin = originOf(uri);
    boolean sameOrigin = origin.equalsIgnoreCase(redirectOrigin);
    boolean inCors = allowedOrigins.contains(origin.toLowerCase(Locale.ROOT));
    if (!sameOrigin && !inCors) {
      throw ApiException.bad("返回地址不在允许的域名白名单内");
    }
    return uri.toString();
  }

  static URI parseHttpUri(String raw, String message) {
    try {
      URI uri = URI.create(raw);
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (scheme == null
          || host == null
          || uri.getUserInfo() != null
          || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
        throw ApiException.bad(message);
      }
      return uri;
    } catch (IllegalArgumentException e) {
      throw ApiException.bad(message);
    }
  }

  static String originOf(URI uri) {
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    int port = uri.getPort();
    boolean standardPort =
        port == -1
            || ("http".equals(scheme) && port == 80)
            || ("https".equals(scheme) && port == 443);
    return scheme + "://" + host + (standardPort ? "" : ":" + port);
  }

  static boolean isLoopback(String host) {
    return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
  }
}
