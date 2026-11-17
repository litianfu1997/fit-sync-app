-- Strava 官方 OAuth 2.0 授权流程状态表；回调仅接收一次性高熵 state。
CREATE TABLE oauth_challenge (
  id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
  platform VARCHAR(32) NOT NULL,
  state_hash VARCHAR(64) NOT NULL UNIQUE,
  status VARCHAR(20) NOT NULL,
  encrypted_context TEXT NOT NULL,
  message VARCHAR(300),
  expires_at BIGINT NOT NULL
);
CREATE INDEX oauth_challenge_expiry_idx ON oauth_challenge(expires_at);
