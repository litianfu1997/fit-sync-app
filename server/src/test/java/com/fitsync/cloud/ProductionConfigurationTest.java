package com.fitsync.cloud;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationTest {
  @Test
  void productionCannotFallBackToLocalEncryptionKey() {
    var env = new MockEnvironment();
    env.setActiveProfiles("prod");
    assertThrows(
        IllegalStateException.class,
        () -> new CryptoService("", "./target/prod-not-created/fits", env));
  }

  @Test
  void mixedProfilesCannotEnableDevelopmentLogin() {
    var env = new MockEnvironment();
    env.setActiveProfiles("local", "prod");
    assertThrows(
        IllegalStateException.class,
        () ->
            new AuthService(
                mock(CloudStore.class),
                mock(CryptoService.class),
                mock(PasswordService.class),
                mock(EmailService.class),
                env,
                "appid",
                "secret",
                true,
                30));
  }

  @Test
  void productionRequiresWechatConfiguration() {
    var env = new MockEnvironment();
    env.setActiveProfiles("prod");
    assertThrows(
        IllegalStateException.class,
        () ->
            new AuthService(
                mock(CloudStore.class),
                mock(CryptoService.class),
                mock(PasswordService.class),
                mock(EmailService.class),
                env,
                "",
                "",
                false,
                30));
  }
}
