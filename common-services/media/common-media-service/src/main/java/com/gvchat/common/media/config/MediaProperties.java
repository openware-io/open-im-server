package com.gvchat.common.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 媒体存储与限额配置（media.*）。
 *
 * <p>该配置保持业务中立：图片、音视频与附件的对象存储、限额与公开访问前缀全部由媒体服务拥有，
 * 业务服务不得各自持有对象存储凭据。public-bucket / public-url-prefix 是跨业务共用的公共图片能力配置，
 * 不使用任何 IM 语义命名。
 *
 * <ul>
 *   <li>{@code media.public-bucket}：公开只读桶，默认 {@code gv-media-public}。</li>
 *   <li>{@code media.public-url-prefix}：对外公开访问前缀，默认走网关 {@code /api} 命名空间
 *   {@code /api/v1/media-public}；各环境 {@code /api} 都路由到网关，由网关 StripPrefix 剥成对象存储的
 *   {@code /{bucket}/{objectKey}}，不依赖任何入口 rewrite。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "media")
public record MediaProperties(
    String provider,
    String endpoint,
    String publicBaseUrl,
    String region,
    String accessKey,
    String secretKey,
    String privateBucket,
    long uploadSessionTtlSeconds,
    long accessUrlTtlSeconds,
    long multipartThresholdBytes,
    long maxImageBytes,
    long maxAudioBytes,
    long maxAttachmentBytes,
    long maxVideoBytes,
    long maxAudioDurationMs,
    long maxVideoDurationMs,
    long maxImagePixels,
    String ffprobePath,
    long probeTimeoutSeconds,
    String allowedImageTypes,
    String allowedAudioTypes,
    String allowedVideoTypes,
    String allowedAttachmentTypes,
    String allowedVoucherTypes,
    String publicBucket,
    String publicUrlPrefix) {

  public MediaProperties {
    publicBucket = blankToDefault(publicBucket, "gv-media-public");
    publicUrlPrefix = blankToDefault(publicUrlPrefix, "/api/v1/media-public");
  }

  /** 对外公开访问前缀（去掉尾部斜杠）。 */
  public String normalizedPublicUrlPrefix() {
    return publicUrlPrefix.replaceAll("/+$", "");
  }

  /** 图片上限的整 MB 展示值（仅用于错误提示）。 */
  public long maxImageMegabytes() {
    return Math.max(1L, maxImageBytes / 1024 / 1024);
  }

  private static String blankToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
