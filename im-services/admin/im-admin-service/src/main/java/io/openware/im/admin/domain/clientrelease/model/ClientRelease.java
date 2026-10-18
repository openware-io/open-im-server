package io.openware.im.admin.domain.clientrelease.model;

import java.time.LocalDateTime;
import java.util.List;

public record ClientRelease(Long id, ReleasePlatform platform, ReleaseChannel channel, String releaseVersion,
                            Long buildNumber, ReleaseStatus status, boolean mandatory, int rolloutPercent,
                            String rolloutSalt, LocalDateTime scheduledAt, LocalDateTime publishedAt,
                            String releaseNotes, String storeUrl, CompatibilitySnapshot compatibility,
                            Long rowVersion, List<ReleaseArtifact> artifacts) { }
