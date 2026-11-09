-- 新增 FitSync 用户名 / 密码账号。已有微信用户保持无密码，可登录后补设。
-- 用户名统一存小写，由唯一索引保证大小写不敏感；空值表示该用户尚未设置密码账号。
ALTER TABLE app_user ADD COLUMN username VARCHAR(64);
ALTER TABLE app_user ADD COLUMN password_hash VARCHAR(255);
CREATE UNIQUE INDEX app_user_username_unique ON app_user(username);
