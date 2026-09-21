package com.gvchat.common.media.media;

import com.gvchat.common.media.config.MediaProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** COS is S3-compatible; provider-specific endpoints and credentials are supplied by deployment configuration. */
@Component
@ConditionalOnProperty(prefix = "media", name = "provider", havingValue = "cos")
public class S3CompatibleMediaStorage extends MinioMediaStorage {
  public S3CompatibleMediaStorage(MediaProperties properties) {
    super(properties);
  }
}
