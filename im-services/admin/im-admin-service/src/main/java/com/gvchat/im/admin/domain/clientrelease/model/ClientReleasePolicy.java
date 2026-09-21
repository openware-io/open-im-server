package com.gvchat.im.admin.domain.clientrelease.model;
import java.time.LocalDateTime;
public record ClientReleasePolicy(Long id, ReleasePlatform platform, ReleaseChannel channel, Long minimumBuildNumber, Long rowVersion, Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) { }
