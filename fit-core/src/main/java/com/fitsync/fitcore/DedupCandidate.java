package com.fitsync.fitcore;

import com.fitsync.app.domain.model.SportType;
import java.util.List;

/** 去重领域输入，不携带数据库或 Android 依赖。 */
public final class DedupCandidate {
  private final long id;
  private final SportType sportType;
  private final long startTimeEpochMs;
  private final Integer elapsedSeconds;
  private final Integer movingSeconds;
  private final Double distanceMeters;
  private final List<TrackPoint> track;

  public DedupCandidate(
      long id,
      String sportType,
      long startTimeEpochMs,
      Integer elapsedSeconds,
      Integer movingSeconds,
      Double distanceMeters,
      List<TrackPoint> track) {
    this.id = id;
    this.sportType = SportType.valueOf(sportType);
    this.startTimeEpochMs = startTimeEpochMs;
    this.elapsedSeconds = elapsedSeconds;
    this.movingSeconds = movingSeconds;
    this.distanceMeters = distanceMeters;
    this.track = track == null ? List.of() : List.copyOf(track);
  }

  public long getId() {
    return id;
  }

  public SportType getSportType() {
    return sportType;
  }

  public long getStartTimeEpochMs() {
    return startTimeEpochMs;
  }

  public Integer getElapsedSeconds() {
    return elapsedSeconds;
  }

  public Integer getMovingSeconds() {
    return movingSeconds;
  }

  public Double getDistanceMeters() {
    return distanceMeters;
  }

  public List<TrackPoint> getTrack() {
    return track;
  }
}
