package io.openware.im.admin.domain.clientrelease.model;

public record ReleaseArtifact(Long id, Long releaseId, TargetArchitecture architecture, PackageType packageType,
                              String downloadUrl, String sha256, Long sizeBytes, String signingMetadataJson) { }
