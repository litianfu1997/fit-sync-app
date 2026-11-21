package com.fitsync.cloud;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PasswordServiceTest {
  private final PasswordService service = new PasswordService();

  @Test
  void hashIsSaltedAndNeverContainsPlaintext() {
    String a = service.hash("correct-horse-battery"), b = service.hash("correct-horse-battery");
    assertNotEquals(a, b);
    assertTrue(a.startsWith("pbkdf2$210000$"));
    assertFalse(a.contains("correct-horse-battery"));
    assertTrue(service.matches("correct-horse-battery", a));
    assertTrue(service.matches("correct-horse-battery", b));
    assertFalse(service.matches("correct-horse-battery-staple", a));
    assertFalse(service.matches(null, a));
    assertFalse(service.matches("correct-horse-battery", null));
    assertFalse(service.matches("correct-horse-battery", "not-a-hash"));
    assertFalse(service.matches("correct-horse-battery", "pbkdf2$0$AA$AA"));
  }

  @Test
  void usernameIsNormalizedAndValidated() {
    assertEquals("rider_one", service.validUsername("  Rider_One  "));
    assertEquals("骑友123", service.validUsername("骑友123"));
    assertThrows(ApiException.class, () -> service.validUsername("ab"));
    assertThrows(ApiException.class, () -> service.validUsername("a".repeat(33)));
    assertThrows(ApiException.class, () -> service.validUsername("bad name"));
    assertThrows(ApiException.class, () -> service.validUsername("user@example.com"));
    assertThrows(ApiException.class, () -> service.validUsername(null));
  }

  @Test
  void passwordLengthIsEnforced() {
    assertDoesNotThrow(() -> service.validPassword("12345678", "密码"));
    assertThrows(ApiException.class, () -> service.validPassword("1234567", "密码"));
    assertThrows(ApiException.class, () -> service.validPassword("a".repeat(129), "密码"));
    assertThrows(ApiException.class, () -> service.validPassword(null, "密码"));
  }
}
