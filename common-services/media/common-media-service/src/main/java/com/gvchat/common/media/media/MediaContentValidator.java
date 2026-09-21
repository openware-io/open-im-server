package com.gvchat.common.media.media;

import com.gvchat.common.media.config.MediaProperties;
import com.gvchat.common.media.infra.persistence.media.po.MediaObjectPo;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MediaContentValidator {
  private final MediaProperties properties;
  private final MediaStoragePort storage;

  public ValidationResult validate(MediaObjectPo object) {
    if (object.getContentType().startsWith("image/")) return validateImage(object);
    if (object.getContentType().startsWith("audio/") || object.getContentType().startsWith("video/")) {
      return validateAudioVideo(object);
    }
    return ValidationResult.allow();
  }

  private ValidationResult validateImage(MediaObjectPo object) {
    if ("image/webp".equals(object.getContentType())) {
      // JDK ImageIO has no WebP reader; the mandatory signature check runs before this validator.
      return ValidationResult.allow();
    }
    try (InputStream content = storage.open(object.getBucketName(), object.getObjectKey())) {
      BufferedImage image = ImageIO.read(content);
      if (image == null || (long) image.getWidth() * image.getHeight() > properties.maxImagePixels()) {
        return ValidationResult.deny("Invalid image content or pixel count");
      }
      return ValidationResult.allow();
    } catch (Exception exception) {
      return ValidationResult.deny("Image decoding failed");
    }
  }

  private ValidationResult validateAudioVideo(MediaObjectPo object) {
    Path temporaryFile = null;
    try (InputStream content = storage.open(object.getBucketName(), object.getObjectKey())) {
      temporaryFile = Files.createTempFile("media-probe-", "." + extension(object.getContentType()));
      Files.copy(content, temporaryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      Process process = new ProcessBuilder(properties.ffprobePath(), "-v", "error", "-show_entries",
          "format=duration:stream=codec_type,codec_name,width,height", "-of", "default=noprint_wrappers=1",
          temporaryFile.toString()).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(properties.probeTimeoutSeconds(), TimeUnit.SECONDS) || process.exitValue() != 0) {
        return ValidationResult.deny("Audio/video probing failed");
      }
      Double duration = output.lines().filter(line -> line.startsWith("duration=")).map(line -> line.substring(9))
          .map(this::parseNumber).filter(java.util.Objects::nonNull).findFirst().orElse(null);
      if (duration == null) return ValidationResult.deny("Audio/video duration is unavailable");
      long durationMs = Math.round(duration * 1000);
      long limit = object.getMediaKind().equals("audio") ? properties.maxAudioDurationMs() : properties.maxVideoDurationMs();
      if (durationMs <= 0 || durationMs > limit) return ValidationResult.deny("Audio/video duration exceeds the limit");
      object.setDurationMs(durationMs);
      return ValidationResult.allow();
    } catch (Exception exception) {
      return ValidationResult.deny("Audio/video probing is unavailable");
    } finally {
      if (temporaryFile != null) {
        try { Files.deleteIfExists(temporaryFile); } catch (Exception ignored) { }
      }
    }
  }

  private String extension(String contentType) {
    return contentType.substring(contentType.indexOf('/') + 1).replace("jpeg", "jpg");
  }

  private Double parseNumber(String value) {
    try { return Double.valueOf(value); } catch (NumberFormatException exception) { return null; }
  }

  public record ValidationResult(boolean accepted, String reason) {
    static ValidationResult allow() { return new ValidationResult(true, null); }
    static ValidationResult deny(String reason) { return new ValidationResult(false, reason); }
  }
}
