package com.fitsync.cloud;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CloudStore {
  public final JdbcTemplate db;
  public final ObjectMapper json;

  public CloudStore(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> owned(String table, String id, String user) {
    if (!Set.of("activity", "platform_account", "sync_job", "sync_rule").contains(table))
      throw new IllegalArgumentException();
    return one("SELECT * FROM " + table + " WHERE id=? AND user_id=?", id, user);
  }

  public String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("数据编码失败", e);
    }
  }

  public Map<String, Object> decode(String value) {
    try {
      return json.readValue(value, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("数据解码失败", e);
    }
  }

  public static String str(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), null);
  }

  public static long number(Map<String, Object> row, String key) {
    return ((Number) row.getOrDefault(key, 0L)).longValue();
  }
}
