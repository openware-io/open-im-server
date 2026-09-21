package com.gvchat.im.admin.domain.clientrelease.model;

import java.time.LocalDateTime;
import java.util.List;

public record ClientRelease(Long id, ReleasePlatform platform, ReleaseChannel channel, String version,
                            Long buildNumber, ReleaseStatus status, boolean mandatory, int rolloutPercent,
                            String rolloutSalt, LocalDateTime scheduledAt, LocalDateTime publishedAt,
                            String releaseNotes, String storeUrl, CompatibilitySnapshot compatibility,
                            Long rowVersion, Long createdBy, LocalDateTime createdAt, Long updatedBy,
                            LocalDateTime updatedAt, List<ReleaseArtifact> artifacts) { }
