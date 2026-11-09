package com.fitsync.cloud.connector;

/** 仅含目标平台已确认的真实活动 ID；禁止放 OSS key、MD5 或本地合成 ID。 */
public record UploadResult(String remoteActivityId) {}
