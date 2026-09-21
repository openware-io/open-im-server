package com.gvchat.common.media.media;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.common.media.config.MediaProperties;
import java.io.InputStream;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 业务无关的公开图片上传能力：内部服务把图片字节交给媒体服务，媒体服务校验后写入中性公开桶，
 * 返回可直接引用的同源 URL 与对象键。
 *
 * <p>该能力不绑定任何业务与用户态：不写媒体归属/引用表、不要求上传会话，
 * 业务行自行保存返回的 URL（引用与清理由业务服务负责）。
 *
 * <p>对象键规则：{@code {biz}/{scope}/{yyyyMM}/{uuid}.{ext}}；{@code scope} 为空时省略该段，
 * {@code biz} 为空时使用 {@code public}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaImageUploadService {
  private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");
  private static final String DEFAULT_BIZ = "public";
  private static final int SEGMENT_MAX_LENGTH = 64;
  /**
   * 公共图片对象元数据：对象键含 UUID、内容不可变，故按长期不可变资源缓存。
   *
   * <p>写入对象本身而非只在响应上设置，匿名 GetObject 时由对象存储回放，网关只做转发，
   * 浏览器与 CDN 侧因此仍保留缓存语义（此前由各环境入口的反代负责，已随规范 URL 改造移除）。
   */
  static final String PUBLIC_IMAGE_CACHE_CONTROL = "public, max-age=31536000, immutable";

  private final MediaProperties properties;
  private final MediaStoragePort storage;

  /** 校验图片类型与大小，写入公开桶并返回访问信息。 */
  public UploadedImage upload(String biz, String scope, String contentType, long size, InputStream content) {
    String type = imageType(contentType);
    if (size <= 0) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "MEDIA_FILE_REQUIRED", "请选择要上传的图片");
    }
    if (size > properties.maxImageBytes()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "MEDIA_TOO_LARGE",
          "图片大小不能超过 %dMB".formatted(properties.maxImageMegabytes()));
    }
    String bucket = properties.publicBucket();
    String objectKey = objectKey(biz, scope, type);
    storage.ensurePublicReadBucket(bucket);
    storage.putObject(bucket, objectKey, type, content, size,
        java.util.Map.of("Cache-Control", PUBLIC_IMAGE_CACHE_CONTROL));
    log.info("Public image stored: bucket={}, objectKey={}, contentType={}, size={}", bucket, objectKey, type, size);
    return new UploadedImage(publicUrl(bucket, objectKey), objectKey, bucket, type, size);
  }

  /**
   * 对外同源 URL：{public-url-prefix}/{bucket}/{objectKey}。
   *
   * <p>默认前缀为 /api/v1/media-public，落在所有环境都路由到网关的 /api 命名空间，
   * 由网关剥掉 api/v1/media-public 三段后转发给对象存储；不使用绝对地址。
   */
  public String publicUrl(String bucket, String objectKey) {
    return properties.normalizedPublicUrlPrefix() + "/" + bucket + "/" + objectKey;
  }

  /** 对象键：{biz}/{scope}/{yyyyMM}/{uuid}.{ext}；scope 为空时省略；biz/scope 仅保留安全字符。 */
  String objectKey(String biz, String scope, String contentType) {
    StringBuilder key = new StringBuilder(segment(biz, DEFAULT_BIZ)).append('/');
    String scopeSegment = segment(scope, null);
    if (scopeSegment != null) {
      key.append(scopeSegment).append('/');
    }
    return key.append(YearMonth.now(ZoneOffset.UTC).format(MONTH_FORMAT)).append('/')
        .append(UUID.randomUUID().toString().replace("-", "")).append('.').append(extension(contentType))
        .toString();
  }

  /** 归一化并校验图片 MIME：仅允许 media.allowed-image-types。 */
  private String imageType(String contentType) {
    String type = normalize(contentType);
    if (!allowedImageTypes().contains(type)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "MEDIA_TYPE_NOT_ALLOWED", "仅支持 JPG/PNG/WebP 图片");
    }
    return type;
  }

  private Set<String> allowedImageTypes() {
    Set<String> types = new LinkedHashSet<>();
    Arrays.stream(properties.allowedImageTypes().split(",")).map(this::normalize)
        .filter(value -> !value.isEmpty()).forEach(types::add);
    return types;
  }

  private String normalize(String contentType) {
    if (contentType == null) {
      return "";
    }
    String type = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
    return "image/jpg".equals(type) ? "image/jpeg" : type;
  }

  /** 只保留 [A-Za-z0-9._-]，避免调用方通过 biz/scope 注入路径；空值返回 fallback。 */
  private String segment(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    String sanitized = value.trim().replaceAll("[^A-Za-z0-9._-]+", "-")
        .replaceAll("^[.-]+", "").replaceAll("[.-]+$", "");
    if (sanitized.isEmpty()) {
      return fallback;
    }
    String trimmed = sanitized.length() > SEGMENT_MAX_LENGTH ? sanitized.substring(0, SEGMENT_MAX_LENGTH) : sanitized;
    if (!trimmed.equals(value.trim())) {
      log.debug("Media object key segment sanitized: original={}, sanitized={}", value, trimmed);
    }
    return trimmed;
  }

  private static String extension(String contentType) {
    return switch (contentType) {
      case "image/png" -> "png";
      case "image/webp" -> "webp";
      default -> "jpg";
    };
  }

  /** 上传结果：url 为可直接引用（同源或经网关）的公开地址，objectKey/bucket 便于排查与清理。 */
  public record UploadedImage(String url, String objectKey, String bucket, String contentType, long size) { }
}
