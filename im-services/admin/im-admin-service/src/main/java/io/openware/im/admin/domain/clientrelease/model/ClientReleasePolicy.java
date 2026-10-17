package io.openware.im.admin.domain.clientrelease.model;

public record ClientReleasePolicy(Long id, ReleasePlatform platform, ReleaseChannel channel, Long minimumBuildNumber, Long rowVersion) { }
