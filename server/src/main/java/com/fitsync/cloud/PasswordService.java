package com.fitsync.cloud;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;

/** 业务账号密码散列；与运动平台托管凭据的 AES-GCM 加密分开。 */
@Service
public class PasswordService {
  private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
  private static final int ITERATIONS = 210_000, SALT_BYTES = 16, KEY_BITS = 256;
  private static final int MAX_LENGTH = 128;
  private final SecureRandom random = new SecureRandom();

  static {
    // 校验运行环境支持所选算法，避免配置错误时把失败误判为密码错误。
    try {
      SecretKeyFactory.getInstance(ALGORITHM);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** 校验用户名格式，并返回统一存库的小写形式。 */
  public String validUsername(String raw) {
    if (raw == null) throw ApiException.bad("请输入用户名");
    String value = raw.strip();
    if (value.length() < 3 || value.length() > 32)
      throw ApiException.bad("用户名需为 3–32 个字符");
    if (!value.matches("[A-Za-z0-9_\\u4e00-\\u9fff-]+"))
      throw ApiException.bad("用户名只能包含中英文、数字、下划线或连字符");
    return value.toLowerCase(java.util.Locale.ROOT);
  }

  /** 校验密码长度；密码不记录日志、不加密留存明文。 */
  public String validPassword(String raw, String label) {
    if (raw == null || raw.length() < 8 || raw.length() > MAX_LENGTH)
      throw ApiException.bad(label + "需为 8–128 个字符");
    return raw;
  }

  public String hash(String password) {
    byte[] salt = new byte[SALT_BYTES];
    random.nextBytes(salt);
    return encode(salt, derive(password, salt, ITERATIONS));
  }

  /** 恒定时间比较；格式或参数不合法时返回 false，而不是抛出服务端内部错误。 */
  public boolean matches(String password, String stored) {
    if (password == null || stored == null) return false;
    String[] parts = stored.split("\\$");
    if (parts.length != 4 || !parts[1].matches("\\d+")) return false;
    try {
      int iterations = Integer.parseInt(parts[1]);
      if (iterations < 1 || iterations > 1_000_000) return false;
      byte[] salt = Base64.getDecoder().decode(parts[2]);
      byte[] expected = Base64.getDecoder().decode(parts[3]);
      return MessageDigest.isEqual(expected, derive(password, salt, iterations));
    } catch (Exception e) {
      return false;
    }
  }

  private static String encode(byte[] salt, byte[] hash) {
    return "pbkdf2$"
        + ITERATIONS
        + "$"
        + Base64.getEncoder().encodeToString(salt)
        + "$"
        + Base64.getEncoder().encodeToString(hash);
  }

  private static byte[] derive(String password, byte[] salt, int iterations) {
    try {
      var spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
      try {
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
      } finally {
        spec.clearPassword();
      }
    } catch (Exception e) {
      throw new IllegalStateException("密码处理失败", e);
    }
  }
}
