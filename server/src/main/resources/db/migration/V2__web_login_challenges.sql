-- 浏览器持有轮询秘密；小程序确认后仍只允许原浏览器消费一次。
CREATE TABLE web_login_challenge (id VARCHAR(36) PRIMARY KEY, code_hash VARCHAR(64) NOT NULL UNIQUE, poll_hash VARCHAR(64) NOT NULL, status VARCHAR(20) NOT NULL, confirmed_user_id VARCHAR(36) REFERENCES app_user(id), expires_at BIGINT NOT NULL);
CREATE INDEX web_challenge_expiry_idx ON web_login_challenge(expires_at);
