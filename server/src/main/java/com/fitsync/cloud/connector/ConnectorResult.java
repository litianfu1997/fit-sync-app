package com.fitsync.cloud.connector;

/** 业务与网络错误均以显式状态交给任务状态机处理。 */
public record ConnectorResult<T>(
    Status status,
    T value,
    String code,
    String message,
    String confirmationToken,
    long retryAfterMillis) {
  public enum Status {
    SUCCESS,
    DUPLICATE,
    PROCESSING,
    REQUIRES_LOGIN,
    REQUIRES_USER_ACTION,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE
  }

  public static <T> ConnectorResult<T> success(T value) {
    return new ConnectorResult<>(Status.SUCCESS, value, null, null, null, 0);
  }

  public static <T> ConnectorResult<T> duplicate(T value) {
    return new ConnectorResult<>(Status.DUPLICATE, value, null, "目标平台已有相同活动", null, 0);
  }

  public static <T> ConnectorResult<T> processing(String token) {
    return processing(token, 60_000);
  }

  public static <T> ConnectorResult<T> processing(String token, long retryAfterMillis) {
    return new ConnectorResult<>(
        Status.PROCESSING, null, null, "等待目标平台解析并确认活动", token, Math.max(1_000, retryAfterMillis));
  }

  public static <T> ConnectorResult<T> failure(Status status, String code, String message) {
    return failure(status, code, message, status == Status.RETRYABLE_FAILURE ? 60_000 : 0);
  }

  public static <T> ConnectorResult<T> failure(
      Status status, String code, String message, long retryAfterMillis) {
    return new ConnectorResult<>(
        status, null, code, message, null, Math.max(0, retryAfterMillis));
  }

  public <R> ConnectorResult<R> castFailure() {
    return new ConnectorResult<>(status, null, code, message, confirmationToken, retryAfterMillis);
  }
}
