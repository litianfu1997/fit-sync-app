package com.fitsync.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
  private final String[] origins;

  public WebConfiguration(@Value("${fitsync.cors-origins:}") String origins) {
    this.origins =
        Arrays.stream(origins.split(","))
            .map(String::strip)
            .filter(s -> !s.isBlank())
            .toArray(String[]::new);
    if (Arrays.asList(this.origins).contains("*"))
      throw new IllegalStateException("CORS 必须配置具体域名，不能使用通配符");
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins(origins)
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("Authorization", "Content-Type", "X-Login-Poll-Token")
        .exposedHeaders("Content-Disposition")
        .maxAge(3600);
  }

  @Bean
  public FilterRegistrationBean<CorsFilter> corsFilter() {
    var configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(Arrays.asList(origins));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Login-Poll-Token"));
    configuration.setExposedHeaders(List.of("Content-Disposition"));
    configuration.setMaxAge(3600L);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", configuration);
    var registration = new FilterRegistrationBean<>(new CorsFilter(source));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  @Bean
  public OncePerRequestFilter bearerFilter(
      AuthService auth,
      ObjectMapper json,
      @Value("${fitsync.auth-rate-limit:30}") int authRateLimit) {
    return new OncePerRequestFilter() {
      final java.util.concurrent.ConcurrentHashMap<
              String, java.util.concurrent.atomic.AtomicInteger>
          requests = new java.util.concurrent.ConcurrentHashMap<>();
      volatile long window = System.currentTimeMillis() / 60000;

      @Override
      protected void doFilterInternal(
          HttpServletRequest request, HttpServletResponse response, FilterChain chain)
          throws ServletException, IOException {
        String path = request.getRequestURI();
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (path.startsWith("/api/")) response.setHeader("Cache-Control", "no-store");
        boolean challenge =
            path.startsWith("/api/v1/auth/web-challenges")
                && !path.equals("/api/v1/auth/web-challenges/confirm");
        boolean publicPath =
            path.equals("/api/v1/config")
                || path.equals("/api/v1/auth/dev-login")
                || path.equals("/api/v1/auth/wechat")
                || path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/login")
                || path.equals(StravaOAuthService.CALLBACK_PATH)
                || challenge;
        if (path.startsWith("/api/v1/auth/") && !request.getMethod().equals("OPTIONS")) {
          long current = System.currentTimeMillis() / 60000;
          if (current != window) {
            synchronized (this) {
              if (current != window) {
                requests.clear();
                window = current;
              }
            }
          }
          String category = challenge && request.getMethod().equals("GET") ? "poll" : "auth";
          String key = request.getRemoteAddr() + ":" + category;
          int max = category.equals("poll") ? Math.max(90, authRateLimit) : authRateLimit;
          if (requests.size() > 10000
              || requests
                      .computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicInteger())
                      .incrementAndGet()
                  > max) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json;charset=UTF-8");
            json.writeValue(
                response.getWriter(), Map.of("code", "RATE_LIMITED", "message", "操作过于频繁，请稍后重试"));
            return;
          }
        }
        if (path.startsWith("/api/") && !publicPath && !request.getMethod().equals("OPTIONS")) {
          try {
            request.setAttribute("userId", auth.authenticate(request.getHeader("Authorization")));
          } catch (ApiException e) {
            response.setStatus(e.status);
            response.setContentType("application/json;charset=UTF-8");
            json.writeValue(
                response.getWriter(), Map.of("code", e.code, "message", e.getMessage()));
            return;
          }
        }
        chain.doFilter(request, response);
      }
    };
  }
}
