package com.fitsync.fitcore;

import com.fitsync.app.fit.FitCrc;
import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * 由平台曲线数据（Strava Streams 等）重建标准 FIT 活动文件。
 *
 * 官方 API 不提供原始 FIT 下载时，用时间/轨迹/心率/功率等流合成最小但完整的活动文件：
 * 12 字节文件头 + FileId + Record×N + Lap + Session + Activity，缺测字段写 FIT 无效值哨兵。
 * 输出可被 FitParser/FitEngine 完整解析，也可被 Garmin/COROS 等平台的标准解析器接受。
 */
public final class FitComposer {

  /** 各类曲线；除 time 外都允许为 null 或长度不足（不足部分按缺测处理）。 */
  public static final class Streams {
    public int[] time; // 相对开始时间的秒，必需且严格升序
    public double[][] latlng; // [i] = {lat, lon}
    public double[] distance; // 米
    public double[] altitude; // 米
    public double[] speed; // m/s
    public int[] heartRate; // bpm
    public int[] cadence; // rpm
    public int[] power; // 瓦
    public boolean[] moving;
  }

  private static final long FIT_EPOCH_MS = 631_065_600_000L;
  private static final double SEMICIRCLES_PER_DEGREE = 2147483648.0 / 180.0;
  private static final int MAX_SPEED_MILLI = 30_000; // 解析侧把 >30 m/s 视为缺测
  private static final double MIN_ALTITUDE = -499.0;
  private static final double MAX_ALTITUDE = 6000.0;

  // base type（little endian 高位置 1）
  private static final int BASE_U8 = 0x02;
  private static final int BASE_U16 = 0x84;
  private static final int BASE_U32 = 0x86;
  private static final int BASE_S16 = 0x83;
  private static final int BASE_S32 = 0x85;

  private FitComposer() {}

  public static byte[] compose(long startTimeEpochMs, String sportType, Streams streams) {
    int[] time = streams.time;
    if (time == null || time.length < 2)
      throw new IllegalArgumentException("曲线缺少有效时间序列");
    for (int i = 1; i < time.length; i++) {
      if (time[i] <= time[i - 1]) throw new IllegalArgumentException("曲线时间序列必须严格升序");
    }
    int count = time.length;
    long elapsed = time[count - 1] - time[0];
    long startMs = startTimeEpochMs + time[0] * 1000L;

    double totalDistance = lastValue(streams.distance, count, -1.0);
    long movingSeconds = movingSeconds(streams, time, elapsed);
    int ascent = totalAscent(streams.altitude, count);
    int avgHr = roundedAverage(intAverage(streams.heartRate, count));
    int avgCad = roundedAverage(intAverage(streams.cadence, count));
    int avgPower = roundedAverage(intAverage(streams.power, count));

    ByteArrayOutputStream out = new ByteArrayOutputStream(64 + count * 26);
    writeHeaderPlaceholder(out);

    writeDefinition(out, 0, 0, field(2, 2, BASE_U16), field(5, 1, BASE_U8));
    ByteArrayOutputStream fileId = new ByteArrayOutputStream(3);
    putU16(fileId, 255); // manufacturer=development，避免伪装真实厂商
    putU8(fileId, 4); // type=activity
    out.write(0);
    append(out, fileId);

    writeDefinition(
        out,
        1,
        20,
        field(253, 4, BASE_U32),
        field(0, 4, BASE_S32),
        field(1, 4, BASE_S32),
        field(3, 1, BASE_U8),
        field(4, 1, BASE_U8),
        field(5, 4, BASE_U32),
        field(73, 4, BASE_U32),
        field(7, 2, BASE_U16),
        field(78, 2, BASE_S16));
    for (int i = 0; i < count; i++) {
      long fitSeconds = epochSeconds(startMs) + (time[i] - time[0]);
      ByteArrayOutputStream record = new ByteArrayOutputStream(26);
      putU32(record, fitSeconds);
      putLatLng(record, streams.latlng, i);
      putU8(record, intAt(streams.heartRate, i) ? clampU8(streams.heartRate[i]) : 0xFF);
      putU8(record, intAt(streams.cadence, i) ? clampU8(streams.cadence[i]) : 0xFF);
      double distance = doubleAt(streams.distance, i) ? streams.distance[i] : Double.NaN;
      putU32(record, distance >= 0 && distance < 42_949_672.0 ? Math.round(distance * 100.0) : -1);
      double speed = doubleAt(streams.speed, i) ? streams.speed[i] : Double.NaN;
      long milliSpeed =
          speed >= 0 && speed * 1000.0 <= MAX_SPEED_MILLI ? Math.round(speed * 1000.0) : -1;
      putU32(record, milliSpeed);
      putU16(record, intAt(streams.power, i) ? clampU16(streams.power[i]) : 0xFFFF);
      double altitude = doubleAt(streams.altitude, i) ? streams.altitude[i] : Double.NaN;
      if (Double.isNaN(altitude)) putU16(record, 0x7FFF);
      else {
        double bounded = Math.min(MAX_ALTITUDE, Math.max(MIN_ALTITUDE, altitude));
        putU16(record, (int) Math.round((bounded + 500.0) * 5.0) & 0xFFFF);
      }
      out.write(1);
      append(out, record);
    }

    writeDefinition(out, 2, 19, field(253, 4, BASE_U32), field(0, 2, BASE_U16), field(22, 2, BASE_U16));
    ByteArrayOutputStream lap = new ByteArrayOutputStream(8);
    putU32(lap, epochSeconds(startMs) + elapsed);
    putU16(lap, 0);
    putU16(lap, clampU16(ascent));
    out.write(2);
    append(out, lap);

    writeDefinition(
        out,
        3,
        18,
        field(253, 4, BASE_U32),
        field(2, 4, BASE_U32),
        field(5, 1, BASE_U8),
        field(7, 4, BASE_U32),
        field(8, 4, BASE_U32),
        field(9, 4, BASE_U32),
        field(16, 1, BASE_U8),
        field(18, 1, BASE_U8),
        field(20, 2, BASE_U16),
        field(22, 2, BASE_U16));
    ByteArrayOutputStream session = new ByteArrayOutputStream(27);
    putU32(session, epochSeconds(startMs) + elapsed);
    putU32(session, epochSeconds(startMs));
    putU8(session, fitSport(sportType));
    putU32(session, elapsed * 1000L);
    putU32(session, movingSeconds * 1000L);
    putU32(session, totalDistance >= 0 ? Math.round(totalDistance * 100.0) : 0xFFFFFFFFL);
    putU8(session, avgHr >= 0 ? clampU8(avgHr) : 0xFF);
    putU8(session, avgCad >= 0 ? clampU8(avgCad) : 0xFF);
    putU16(session, avgPower >= 0 ? clampU16(avgPower) : 0xFFFF);
    putU16(session, clampU16(ascent));
    out.write(3);
    append(out, session);

    writeDefinition(
        out, 4, 34, field(1, 1, BASE_U8), field(2, 1, BASE_U8), field(4, 2, BASE_U16), field(253, 4, BASE_U32));
    ByteArrayOutputStream activity = new ByteArrayOutputStream(8);
    putU8(activity, 0); // event=timer
    putU8(activity, 4); // event_type=stop_all
    putU16(activity, 1); // num_sessions
    putU32(activity, epochSeconds(startMs) + elapsed);
    out.write(4);
    append(out, activity);

    return finish(out);
  }

  private static long epochSeconds(long epochMs) {
    return epochMs / 1000L - FIT_EPOCH_MS / 1000L;
  }

  private static void putLatLng(ByteArrayOutputStream out, double[][] latlng, int index) {
    if (latlng != null && index < latlng.length && latlng[index] != null && latlng[index].length >= 2) {
      double lat = latlng[index][0];
      double lon = latlng[index][1];
      if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && (lat != 0.0 || lon != 0.0)) {
        putU32(out, (int) Math.round(lat * SEMICIRCLES_PER_DEGREE));
        putU32(out, (int) Math.round(lon * SEMICIRCLES_PER_DEGREE));
        return;
      }
    }
    putU32(out, 0x7FFFFFFF);
    putU32(out, 0x7FFFFFFF);
  }

  private static boolean intAt(int[] values, int index) {
    return values != null && index < values.length && values[index] >= 0;
  }

  private static boolean doubleAt(double[] values, int index) {
    return values != null && index < values.length && !Double.isNaN(values[index]);
  }

  private static double lastValue(double[] values, int count, double fallback) {
    for (int i = Math.min(count, values == null ? 0 : values.length) - 1; i >= 0; i--) {
      if (!Double.isNaN(values[i])) return values[i];
    }
    return fallback;
  }

  private static long movingSeconds(Streams streams, int[] time, long elapsed) {
    boolean[] moving = streams.moving;
    if (moving == null || moving.length < 2) return elapsed;
    long movingMs = 0;
    for (int i = 1; i < time.length; i++) {
      if (i < moving.length && moving[i]) movingMs += time[i] - time[i - 1];
    }
    return Math.min(Math.max(movingMs, 0), elapsed);
  }

  private static int totalAscent(double[] altitude, int count) {
    if (altitude == null) return 0;
    Double previous = null;
    double ascent = 0;
    for (int i = 0; i < count && i < altitude.length; i++) {
      if (Double.isNaN(altitude[i])) continue;
      if (previous != null && altitude[i] > previous) ascent += altitude[i] - previous;
      previous = altitude[i];
    }
    return (int) Math.round(Math.min(ascent, 65534));
  }

  private static Double intAverage(int[] values, int count) {
    if (values == null) return null;
    long sum = 0;
    int n = 0;
    for (int i = 0; i < count && i < values.length; i++) {
      if (values[i] >= 0) {
        sum += values[i];
        n++;
      }
    }
    return n == 0 ? null : sum / (double) n;
  }

  private static int roundedAverage(Double average) {
    return average == null ? -1 : (int) Math.round(average);
  }

  private static int fitSport(String sportType) {
    String sport = sportType == null ? "" : sportType.trim().toLowerCase(Locale.ROOT);
    if (sport.isEmpty()) return 0;
    if (sport.contains("ride") || sport.contains("cycling") || sport.contains("bike")) return 2;
    if (sport.contains("run") || sport.contains("jog") || sport.contains("walk")) return 1;
    return 0;
  }

  private static int clampU8(int value) {
    return Math.min(Math.max(value, 0), 254);
  }

  private static int clampU16(int value) {
    return Math.min(Math.max(value, 0), 65534);
  }

  private static int[] field(int num, int size, int base) {
    return new int[] {num, size, base};
  }

  private static void writeDefinition(ByteArrayOutputStream out, int local, int global, int[]... fields) {
    out.write(0x40 | local);
    out.write(0); // reserved
    out.write(0); // architecture=0 little endian
    putU16(out, global);
    out.write(fields.length);
    for (int[] f : fields) {
      out.write(f[0]);
      out.write(f[1]);
      out.write(f[2]);
    }
  }

  private static void writeHeaderPlaceholder(ByteArrayOutputStream out) {
    for (int i = 0; i < 12; i++) out.write(0);
  }

  private static byte[] finish(ByteArrayOutputStream out) {
    byte[] body = out.toByteArray();
    byte[] bytes = new byte[body.length + 2]; // 末尾 2 字节留给 CRC
    System.arraycopy(body, 0, bytes, 0, body.length);
    long dataLength = bytes.length - 12 - 2;
    bytes[0] = 12; // header size
    bytes[1] = 0x10; // protocol 1.0
    bytes[2] = 100; // profile version
    bytes[3] = 0;
    for (int i = 0; i < 4; i++) bytes[4 + i] = (byte) ((dataLength >>> (i * 8)) & 0xFF);
    bytes[8] = '.';
    bytes[9] = 'F';
    bytes[10] = 'I';
    bytes[11] = 'T';
    int crc = FitCrc.INSTANCE.compute(bytes, 0, bytes.length - 2);
    bytes[bytes.length - 2] = (byte) (crc & 0xFF);
    bytes[bytes.length - 1] = (byte) ((crc >>> 8) & 0xFF);
    return bytes;
  }

  /** 内存 ByteArrayOutputStream 之间搬运不会真正发生 IO，受检异常直接转非法状态。 */
  private static void append(ByteArrayOutputStream target, ByteArrayOutputStream source) {
    try {
      source.writeTo(target);
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException("内存流写入失败", impossible);
    }
  }

  private static void putU8(ByteArrayOutputStream out, long value) {
    out.write((int) (value & 0xFF));
  }

  private static void putU16(ByteArrayOutputStream out, long value) {
    out.write((int) (value & 0xFF));
    out.write((int) ((value >>> 8) & 0xFF));
  }

  private static void putU32(ByteArrayOutputStream out, long value) {
    for (int i = 0; i < 4; i++) out.write((int) ((value >>> (i * 8)) & 0xFF));
  }
}
