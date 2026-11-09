package com.fitsync.cloud.connector;

import java.util.Map;

public record RemoteActivity(
    String remoteActivityId,
    Long startTimeEpochMs,
    String name,
    Double distanceMeters,
    String downloadUrl,
    Map<String, String> metadata) {
  public RemoteActivity {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}
