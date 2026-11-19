-- 为自动同步规则补充活动类型过滤配置（NULL 表示全部活动类型）。
ALTER TABLE sync_rule ADD COLUMN sport_types VARCHAR(255);
