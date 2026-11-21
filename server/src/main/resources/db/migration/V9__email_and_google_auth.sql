-- 扩展用户表：支持邮箱与 Google 账号标识
ALTER TABLE app_user ADD COLUMN email VARCHAR(160);
ALTER TABLE app_user ADD COLUMN google_sub VARCHAR(128);
CREATE UNIQUE INDEX app_user_email_unique ON app_user(email);
CREATE UNIQUE INDEX app_user_google_sub_unique ON app_user(google_sub);

-- 邮箱验证码表（仅存 SHA-256 哈希，不落盘明文验证码）
CREATE TABLE email_verification_code (
  id VARCHAR(36) PRIMARY KEY,
  email VARCHAR(160) NOT NULL,
  purpose VARCHAR(24) NOT NULL,
  code_hash VARCHAR(64) NOT NULL,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL
);
CREATE INDEX email_code_lookup_idx ON email_verification_code(email, purpose, expires_at);

-- Google OAuth 2.0 登录与绑定挑战表
CREATE TABLE google_oauth_challenge (
  id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(36) REFERENCES app_user(id),
  state_hash VARCHAR(64) NOT NULL UNIQUE,
  ticket_hash VARCHAR(64) UNIQUE,
  resolved_user_id VARCHAR(36) REFERENCES app_user(id),
  status VARCHAR(20) NOT NULL,
  encrypted_context TEXT NOT NULL,
  message VARCHAR(300),
  expires_at BIGINT NOT NULL
);
CREATE INDEX google_oauth_expiry_idx ON google_oauth_challenge(expires_at);
