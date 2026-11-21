package com.fitsync.cloud;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class CryptoService {
  private final byte[] key;
  private final SecureRandom random = new SecureRandom();

  public CryptoService(
      @Value("${fitsync.encryption-key:}") String encoded,
      @Value("${fitsync.storage-dir}") String storage,
      Environment env)
      throws Exception {
    boolean local =
        Arrays.asList(env.getActiveProfiles()).contains("local")
            || (env.getActiveProfiles().length == 0
                && Arrays.asList(env.getDefaultProfiles()).contains("local"));
    local = local && !Arrays.asList(env.getActiveProfiles()).contains("prod");
    if (encoded.isBlank()) {
      if (!local)
        throw new IllegalStateException("生产环境必须设置 FITSYNC_ENCRYPTION_KEY（Base64 编码的 32 字节密钥）");
      Path path = Path.of(storage).toAbsolutePath().getParent().resolve("local-encryption.key");
      Files.createDirectories(path.getParent());
      if (!Files.exists(path)) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        try {
          Files.writeString(
              path, Base64.getEncoder().encodeToString(bytes), StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException ignored) {
        }
      }
      encoded = Files.readString(path).trim();
    }
    this.key = Base64.getDecoder().decode(encoded);
    if (key.length != 32) throw new IllegalStateException("加密密钥必须为 32 字节");
  }

  public String encrypt(String plain, String owner) {
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(nonce)
          + "."
          + Base64.getEncoder()
              .encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("凭据加密失败");
    }
  }

  public String decrypt(String encrypted, String owner) {
    try {
      String[] parts = encrypted.split("\\.");
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
      cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
      return new String(
          cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("凭据解密失败，请检查密钥或重新连接平台");
    }
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String token() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
