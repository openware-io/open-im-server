package io.openware.im.admin.application.clientrelease;

import io.openware.im.admin.domain.clientrelease.model.ReleasePlatform;
import java.util.EnumSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Runtime switches and trusted distribution settings for client releases. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "client-release")
public class ClientReleaseProperties {
  private Set<ReleasePlatform> enabledPlatforms = EnumSet.of(ReleasePlatform.ANDROID, ReleasePlatform.IOS);
  private Set<String> allowedDownloadHosts = Set.of();
  private Set<String> supportedProtocolVersions = Set.of("v1");
  private int minimumServerCapabilityVersion = 1;
  private long scheduleFixedDelayMs = 30_000;
  private int scheduleBatchSize = 50;
}
