-- 为云端副本补充来源元数据，并增加远端无效活动跳过记录与按用户查询任务索引。
ALTER TABLE activity_variant ADD COLUMN source_platform VARCHAR(32) NOT NULL DEFAULT 'LOCAL';
ALTER TABLE activity_variant ADD COLUMN remote_id VARCHAR(200);
ALTER TABLE activity_variant ADD COLUMN file_size BIGINT NOT NULL DEFAULT 0;
ALTER TABLE activity_variant ADD COLUMN created_at BIGINT NOT NULL DEFAULT 0;

UPDATE activity_variant
SET source_platform = COALESCE(
    (SELECT a.source_platform FROM activity a WHERE a.id = activity_variant.activity_id),
    'LOCAL')
WHERE source_platform = 'LOCAL';

CREATE TABLE remote_activity_skip (
  id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
  account_id VARCHAR(36) NOT NULL REFERENCES platform_account(id),
  remote_id VARCHAR(200) NOT NULL,
  reason_code VARCHAR(64) NOT NULL,
  message VARCHAR(300),
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  UNIQUE(account_id, remote_id)
);

CREATE INDEX job_user_kind_idx ON sync_job(user_id, kind, created_at);
