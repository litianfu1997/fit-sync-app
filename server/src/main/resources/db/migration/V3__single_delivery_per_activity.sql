-- 同一真实活动的不同质量文件不能在远端确认前产生第二次上传。
CREATE UNIQUE INDEX job_activity_account_unique ON sync_job(activity_id,account_id,kind);
