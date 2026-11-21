package com.fitsync.fitcore;

import static org.junit.jupiter.api.Assertions.*;

import com.fitsync.fitcore.FitComposer.Streams;
import org.junit.jupiter.api.Test;

class FitComposerTest {
  private static final long START_MS = 1_732_896_400_000L; // 2024-11-30 10:00:00 UTC

  @Test
  void roundTripsThroughFitParserWithFullStreams() {
    Streams streams = new Streams();
    streams.time = new int[] {0, 10, 20, 30, 40};
    streams.latlng =
        new double[][] {
          {39.9042, 116.4074},
          {39.9052, 116.4090},
          {39.9060, 116.4110},
          {39.9068, 116.4132},
          {39.9080, 116.4150}
        };
    streams.distance = new double[] {0, 200, 420, 650, 900};
    streams.altitude = new double[] {40, 42, 45, 44, 46};
    streams.speed = new double[] {6.5, 7.0, 7.2, 6.8, 7.1};
    streams.heartRate = new int[] {120, 128, 132, 135, 130};
    streams.cadence = new int[] {88, 90, 92, 91, 90};
    streams.power = new int[] {180, 200, 210, 195, 205};
    streams.moving = new boolean[] {true, true, true, false, true};

    ParsedFit fit = new FitParser().parse(FitComposer.compose(START_MS, "Ride", streams));

    assertEquals("CYCLING", fit.sportType());
    assertEquals(START_MS, fit.startTimeEpochMs());
    assertEquals(40, fit.elapsedSeconds());
    assertEquals(30, fit.movingSeconds());
    assertEquals(900.0, fit.distanceMeters(), 0.01);
    assertEquals(5, fit.track().size());
    assertEquals(39.9042, fit.track().get(0).lat(), 1e-4);
    assertEquals(116.4074, fit.track().get(0).lon(), 1e-4);
    assertEquals(129, fit.avgHeartRate());
    assertEquals(198, fit.avgPower());
    assertEquals(7.0, fit.elevationGain(), 0.01);
    assertEquals(5, fit.profile().size());
    assertEquals(64, fit.sha256().length());
  }

  @Test
  void toleratesMissingStreamsAndSparseSamples() {
    Streams streams = new Streams();
    streams.time = new int[] {0, 60, 120};
    streams.distance = new double[] {0, 300}; // 长度不足：最后一点按缺测
    streams.heartRate = new int[] {-1, 150, 152}; // -1 表示该点缺测

    ParsedFit fit = new FitParser().parse(FitComposer.compose(START_MS, "VirtualRun", streams));

    assertEquals("RUNNING", fit.sportType());
    assertEquals(120, fit.elapsedSeconds());
    assertEquals(120, fit.movingSeconds()); // 无 moving 流时回退为全程
    assertEquals(300.0, fit.distanceMeters(), 0.01);
    assertTrue(fit.track().isEmpty()); // 无轨迹
    assertEquals(151, fit.avgHeartRate()); // 只按有效样本平均
    assertNull(fit.avgPower());
    assertEquals(0.0, fit.elevationGain()); // session 爬升写了 0，不再回退 Record
  }

  @Test
  void rejectsMissingOrUnsortedTimeStream() {
    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class,
            () -> FitComposer.compose(START_MS, "Ride", new Streams()));
    IllegalArgumentException single =
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              Streams s = new Streams();
              s.time = new int[] {5};
              FitComposer.compose(START_MS, "Ride", s);
            });
    IllegalArgumentException unsorted =
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              Streams s = new Streams();
              s.time = new int[] {0, 30, 20};
              FitComposer.compose(START_MS, "Ride", s);
            });
    assertNotNull(missing);
    assertNotNull(single);
    assertNotNull(unsorted);
  }
}
