package com.fitsync.fitcore;

import java.util.List;

public record ParsedFit(
    String sportType,
    long startTimeEpochMs,
    Integer elapsedSeconds,
    Integer movingSeconds,
    Double distanceMeters,
    Double elevationGain,
    Integer avgHeartRate,
    Integer avgPower,
    List<TrackPoint> track,
    List<ProfilePoint> profile,
    int qualityScore,
    String sha256,
    List<String> warnings) {
  public ParsedFit {
    track = List.copyOf(track);
    profile = List.copyOf(profile);
    warnings = List.copyOf(warnings);
  }
}
