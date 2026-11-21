package com.fitsync.fitcore;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class FitParserTest {
  @Test
  void extractsSessionThroughJavaFacade() {
    ParsedFit fit = new FitParser().parse(activity());
    assertEquals("CYCLING", fit.sportType());
    assertEquals(631_066_600_000L, fit.startTimeEpochMs());
    assertEquals(3600, fit.elapsedSeconds());
    assertEquals(3500, fit.movingSeconds());
    assertEquals(42_195.0, fit.distanceMeters(), 0.01);
    assertEquals(64, fit.sha256().length());
  }

  @Test
  void rejectsCrcCorruptionEvenWhenLegacyEngineAllowsWarning() {
    byte[] fit = activity();
    fit[fit.length - 1] ^= 1;
    assertThrows(FitValidationException.class, () -> new FitParser().parse(fit));
  }

  @Test
  void rejectsUnsignedLengthOverflowAndTrailingContent() {
    byte[] fit = activity();
    ByteBuffer.wrap(fit).order(ByteOrder.LITTLE_ENDIAN).putInt(4, -1);
    assertThrows(FitValidationException.class, () -> new FitParser().parse(fit));
    byte[] extra = java.util.Arrays.copyOf(activity(), activity().length + 1);
    assertThrows(FitValidationException.class, () -> new FitParser().parse(extra));
  }

  @Test
  void acceptsSingleFitZipAndRejectsMultipleActivities() throws Exception {
    byte[] raw = activity();
    assertArrayEquals(raw, FitParser.normalize(zip("ride.fit", raw)));
    assertEquals(new FitParser().parse(raw), new FitParser().parse(zip("ride.fit", raw)));
    var output = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(output)) {
      for (String name : List.of("one.fit", "two.fit")) {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(raw);
        zip.closeEntry();
      }
    }
    assertThrows(FitValidationException.class, () -> FitParser.normalize(output.toByteArray()));
  }

  @Test
  void rejectsHtmlAndZipWithoutActivity() throws Exception {
    assertThrows(
        FitValidationException.class,
        () ->
            new FitParser()
                .parse("<html>请重新登录账号</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    byte[] bytes = zip("readme.txt", new byte[20]);
    assertThrows(FitValidationException.class, () -> FitParser.normalize(bytes));
  }

  @Test
  void rejectsEmptyCrcValidFitInsteadOfCreatingPhantomActivity() {
    assertThrows(FitValidationException.class, () -> new FitParser().parse(envelope(new byte[0])));
  }

  @Test
  void dedupKeepsDifferentSportAndOutOfWindowActivitiesSeparate() {
    ParsedFit fit = new FitParser().parse(activity());
    var result =
        new FitDeduplicator()
            .decide(
                activity(),
                List.of(
                    new DedupCandidate(
                        1, "RUNNING", fit.startTimeEpochMs(), 3600, 3500, 42_195.0, List.of()),
                    new DedupCandidate(
                        2,
                        "CYCLING",
                        fit.startTimeEpochMs() + 3_600_000,
                        3600,
                        3500,
                        42_195.0,
                        List.of())));
    assertEquals("CREATE_NEW", result.action());
    assertNull(result.matchedCanonicalId());
  }

  private static byte[] zip(String name, byte[] bytes) throws Exception {
    var output = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry(name));
      zip.write(bytes);
      zip.closeEntry();
    }
    return output.toByteArray();
  }

  static byte[] activity() {
    var body = new ByteArrayOutputStream();
    body.writeBytes(new byte[] {0x40, 0, 0, 0, 0, 1, 0, 1, 0});
    body.writeBytes(new byte[] {0, 4});
    body.writeBytes(
        new byte[] {
          0x41,
          0,
          0,
          18,
          0,
          6,
          2,
          4,
          (byte) 0x86,
          5,
          1,
          0,
          7,
          4,
          (byte) 0x86,
          8,
          4,
          (byte) 0x86,
          9,
          4,
          (byte) 0x86,
          16,
          1,
          2
        });
    body.write(1);
    body.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1000).array());
    body.write(2);
    for (int value : new int[] {3_600_000, 3_500_000, 4_219_500})
      body.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    body.write(148);
    return envelope(body.toByteArray());
  }

  private static byte[] envelope(byte[] data) {
    byte[] bytes = new byte[14 + data.length + 2];
    var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    buffer
        .put((byte) 14)
        .put((byte) 0x10)
        .putShort((short) 2100)
        .putInt(data.length)
        .put(new byte[] {'.', 'F', 'I', 'T'});
    buffer.putShort((short) crc(bytes, 12));
    buffer.put(data).putShort((short) crc(bytes, bytes.length - 2));
    return bytes;
  }

  private static int crc(byte[] bytes, int count) {
    int crc = 0;
    for (int i = 0; i < count; i++) {
      crc ^= bytes[i] & 255;
      for (int b = 0; b < 8; b++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
    }
    return crc;
  }
}
