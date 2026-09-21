package com.gvchat.common.media.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MediaConfigurationValidator {
  private final MediaProperties properties;

  @PostConstruct
  void validate() {
    if (!java.util.Set.of("minio", "cos", "oss").contains(properties.provider()) || blank(properties.endpoint())
        || blank(properties.publicBaseUrl()) || blank(properties.region()) || blank(properties.accessKey())
        || blank(properties.secretKey()) || blank(properties.privateBucket())
        || properties.uploadSessionTtlSeconds() <= 0 || properties.accessUrlTtlSeconds() <= 0
        || properties.multipartThresholdBytes() <= 0 || properties.maxImageBytes() <= 0
        || properties.maxAudioBytes() <= 0 || properties.maxAttachmentBytes() <= 0
        || properties.maxVideoBytes() <= 0 || properties.maxAudioDurationMs() <= 0
        || properties.maxVideoDurationMs() <= 0 || properties.maxImagePixels() <= 0
        || blank(properties.ffprobePath()) || properties.probeTimeoutSeconds() <= 0
        || blank(properties.publicBucket()) || blank(properties.publicUrlPrefix())
        || attachmentTypesInvalid(properties.allowedAttachmentTypes())) {
      throw new IllegalStateException("Invalid managed media storage configuration");
    }
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }

  /** 附件类型白名单必须非空，且不得包含通配或可执行/脚本类危险类型。 */
  private boolean attachmentTypesInvalid(String allowedAttachmentTypes) {
    if (blank(allowedAttachmentTypes)) return true;
    java.util.Set<String> types = java.util.Arrays.stream(allowedAttachmentTypes.split(","))
        .map(String::trim).map(String::toLowerCase).filter(value -> !value.isEmpty())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    return types.isEmpty() || types.contains("*/*") || types.contains("text/html")
        || types.contains("image/svg+xml");
  }
}
