package com.fitsync.cloud;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** 邮箱验证码生成、SMTP 发送与核销服务；验证码仅保存 SHA-256 散列。 */
@Service
public class EmailService {
  public static final long CODE_TTL_MS = Duration.ofMinutes(10).toMillis();
  public static final long COOLDOWN_MS = Duration.ofSeconds(60).toMillis();
  public static final int MAX_ATTEMPTS = 5;

  private final CloudStore store;
  private final PasswordService passwords;
  private final ObjectProvider<JavaMailSender> mailSenderProvider;
  private final String mailHost;
  private final String mailUsername;
  private final String mailPassword;
  private final String mailFrom;
  private final SecureRandom random = new SecureRandom();

  public EmailService(
      CloudStore store,
      PasswordService passwords,
      ObjectProvider<JavaMailSender> mailSenderProvider,
      @Value("${spring.mail.host:}") String mailHost,
      @Value("${spring.mail.username:}") String mailUsername,
      @Value("${spring.mail.password:}") String mailPassword,
      @Value("${fitsync.mail.from:}") String mailFrom) {
    this.store = store;
    this.passwords = passwords;
    this.mailSenderProvider = mailSenderProvider;
    this.mailHost = mailHost == null ? "" : mailHost.strip();
    this.mailUsername = mailUsername == null ? "" : mailUsername.strip();
    this.mailPassword = mailPassword == null ? "" : mailPassword.strip();
    this.mailFrom = mailFrom == null ? "" : mailFrom.strip();
  }

  public boolean isConfigured() {
    return !mailHost.isBlank()
        && !mailUsername.isBlank()
        && !mailPassword.isBlank()
        && mailSenderProvider.getIfAvailable() != null;
  }

  public String normalizePurpose(String raw) {
    if (raw == null || raw.isBlank()) throw ApiException.bad("验证码用途无效");
    String purpose = raw.strip().toUpperCase(Locale.ROOT);
    if (!"REGISTER".equals(purpose) && !"BIND".equals(purpose)) {
      throw ApiException.bad("验证码用途无效");
    }
    return purpose;
  }

  public void sendCode(String rawEmail, String rawPurpose) {
    if (!isConfigured()) throw ApiException.bad("邮件服务尚未配置");
    JavaMailSender sender = mailSenderProvider.getIfAvailable();
    if (sender == null) throw ApiException.bad("邮件服务尚未配置");

    String email = passwords.validEmail(rawEmail);
    String purpose = normalizePurpose(rawPurpose);
    long now = System.currentTimeMillis();

    store.db.update("DELETE FROM email_verification_code WHERE expires_at<?", now - CODE_TTL_MS);

    var recent =
        store.db.queryForList(
            "SELECT created_at FROM email_verification_code WHERE email=? AND purpose=? AND"
                + " created_at>? ORDER BY created_at DESC",
            email,
            purpose,
            now - COOLDOWN_MS);
    if (!recent.isEmpty()) {
      throw ApiException.bad("验证码发送过于频繁，请稍后再试");
    }

    String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    String id = CloudStore.id();
    String codeHash = codeHash(email, purpose, code);

    store.db.update(
        "DELETE FROM email_verification_code WHERE email=? AND purpose=?", email, purpose);
    store.db.update(
        "INSERT INTO"
            + " email_verification_code(id,email,purpose,code_hash,attempt_count,created_at,expires_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        id,
        email,
        purpose,
        codeHash,
        0,
        now,
        now + CODE_TTL_MS);

    try {
      SimpleMailMessage msg = new SimpleMailMessage();
      msg.setFrom(mailFrom.isBlank() ? mailUsername : mailFrom);
      msg.setTo(email);
      msg.setSubject("FitSync 邮箱验证码");
      msg.setText(
          "【FitSync】您的邮箱验证码是："
              + code
              + "，10 分钟内有效。如非本人操作请忽略本邮件。");
      sender.send(msg);
    } catch (Exception e) {
      store.db.update("DELETE FROM email_verification_code WHERE id=?", id);
      throw ApiException.bad("验证码邮件发送失败，请检查邮箱地址或稍后重试");
    }
  }

  public void verifyAndConsume(String rawEmail, String rawPurpose, String rawCode) {
    String email = passwords.validEmail(rawEmail);
    String purpose = normalizePurpose(rawPurpose);
    if (rawCode == null || !rawCode.strip().matches("\\d{6}")) {
      throw ApiException.bad("请输入 6 位数字验证码");
    }
    String code = rawCode.strip();
    long now = System.currentTimeMillis();

    var rows =
        store.db.queryForList(
            "SELECT id,code_hash,attempt_count FROM email_verification_code WHERE email=? AND"
                + " purpose=? AND expires_at>? ORDER BY created_at DESC",
            email,
            purpose,
            now);
    if (rows.isEmpty()) {
      throw ApiException.bad("验证码不存在或已过期，请重新获取");
    }
    var row = rows.getFirst();
    String id = CloudStore.str(row, "id");
    long attempts = CloudStore.number(row, "attempt_count");
    if (attempts >= MAX_ATTEMPTS) {
      store.db.update("DELETE FROM email_verification_code WHERE id=?", id);
      throw ApiException.bad("验证码错误次数过多，请重新获取");
    }

    String expected = CloudStore.str(row, "code_hash");
    String actual = codeHash(email, purpose, code);
    if (expected == null
        || !MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8))) {
      long nextAttempts = attempts + 1;
      if (nextAttempts >= MAX_ATTEMPTS) {
        store.db.update("DELETE FROM email_verification_code WHERE id=?", id);
        throw ApiException.bad("验证码错误次数过多，请重新获取");
      }
      store.db.update(
          "UPDATE email_verification_code SET attempt_count=attempt_count+1 WHERE id=?", id);
      throw ApiException.bad("邮箱验证码不正确");
    }

    store.db.update("DELETE FROM email_verification_code WHERE id=?", id);
  }

  private static String codeHash(String email, String purpose, String code) {
    return CryptoService.hash(email + ":" + purpose + ":" + code);
  }
}
