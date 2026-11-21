package com.fitsync.fitcore;

import com.fitsync.app.domain.model.ValidationStatus;
import com.fitsync.app.fit.FitCrc;
import com.fitsync.app.fit.FitEngine;
import com.fitsync.app.fit.FitParseResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipInputStream;

/** Java 后端入口：原样复用解析算法，并在云端导入边界执行严格完整性检查。 */
public final class FitParser {
  public static final int MAX_BYTES = 50 * 1024 * 1024;

  public ParsedFit parse(byte[] input) {
    byte[] bytes = normalize(input);
    verifyEnvelope(bytes);
    FitParseResult result;
    try {
      result = new FitEngine().parseBytes(bytes);
    } catch (RuntimeException ex) {
      throw new FitValidationException("FIT 内容无法解析");
    }
    if (!result.isActivityFile()
        || result.getStartTimeEpochMs() == null
        || (result.getValidationStatus() != ValidationStatus.VALID
            && result.getValidationStatus() != ValidationStatus.VALID_WITH_WARNINGS)) {
      throw new FitValidationException(
          result.getErrorMessage() == null ? "FIT 中没有可导入的活动或开始时间" : result.getErrorMessage());
    }
    var track =
        result.getDenseTrackSample().isEmpty()
            ? result.getTrackSample()
            : result.getDenseTrackSample();
    return new ParsedFit(
        result.getSportType().name(),
        result.getStartTimeEpochMs(),
        result.getElapsedSeconds(),
        result.getMovingSeconds(),
        result.getDistanceMeters(),
        result.getElevationGain(),
        result.getAvgHeartRate(),
        result.getAvgPower(),
        track.stream().map(p -> new TrackPoint(p.getLat(), p.getLon())).toList(),
        result.getProfileSamples().stream()
            .map(p -> new ProfilePoint(p.getSeconds(), p.getSpeedMps(), p.getAltitudeMeters()))
            .toList(),
        result.getQualityScore(),
        sha256(bytes),
        result.getWarnings());
  }

  /** ZIP 在内存中有界读取，不解压到磁盘；只接受单个 FIT，拒绝歧义归档及压缩炸弹。 */
  public static byte[] normalize(byte[] input) {
    if (input == null || input.length < 14) throw new FitValidationException("文件过短，不是合法 FIT");
    if (input.length > MAX_BYTES) throw new FitValidationException("文件超过 50 MB 上限");
    if (input[0] != 'P' || input[1] != 'K') return input;
    byte[] fit = null;
    long totalBytes = 0;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(input))) {
      int entries = 0;
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (++entries > 128) throw new FitValidationException("ZIP 条目过多");
        boolean selected =
            !entry.isDirectory()
                && entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".fit");
        if (selected && fit != null) throw new FitValidationException("ZIP 含多个 FIT，请逐个导入");
        var output = selected ? new ByteArrayOutputStream() : null;
        byte[] buffer = new byte[8192];
        int count;
        while ((count = zip.read(buffer)) != -1) {
          totalBytes += count;
          if (totalBytes > MAX_BYTES) throw new FitValidationException("ZIP 解压内容超过 50 MB 上限");
          if (output != null) output.write(buffer, 0, count);
        }
        if (output != null) fit = output.toByteArray();
      }
    } catch (IOException ex) {
      throw new FitValidationException("ZIP 文件损坏，无法读取");
    }
    if (fit == null) throw new FitValidationException("ZIP 中没有 FIT 文件");
    return fit;
  }

  private static void verifyEnvelope(byte[] bytes) {
    if (bytes.length < 14) throw new FitValidationException("FIT 文件不完整");
    int header = bytes[0] & 0xff;
    if ((header != 12 && header != 14)
        || bytes[8] != '.'
        || bytes[9] != 'F'
        || bytes[10] != 'I'
        || bytes[11] != 'T') {
      throw new FitValidationException("文件不是 FIT 格式");
    }
    long dataLength = FitCrc.INSTANCE.readUInt32Le(bytes, 4);
    long expectedLength = (long) header + dataLength + 2;
    if (expectedLength != bytes.length) throw new FitValidationException("FIT 长度异常或文件被截断");
    int dataEnd = (int) expectedLength - 2;
    if (FitCrc.INSTANCE.compute(bytes, 0, dataEnd)
        != FitCrc.INSTANCE.readUInt16Le(bytes, dataEnd)) {
      throw new FitValidationException("FIT 文件 CRC 校验失败");
    }
    if (header == 14) {
      int headerCrc = FitCrc.INSTANCE.readUInt16Le(bytes, 12);
      if (headerCrc != 0 && headerCrc != FitCrc.INSTANCE.compute(bytes, 0, 12)) {
        throw new FitValidationException("FIT 文件头 CRC 校验失败");
      }
    }
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("运行环境缺少 SHA-256", ex);
    }
  }
}
