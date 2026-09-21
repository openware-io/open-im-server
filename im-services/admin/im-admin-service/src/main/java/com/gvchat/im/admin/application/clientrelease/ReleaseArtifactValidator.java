package com.gvchat.im.admin.application.clientrelease;

import com.gvchat.im.admin.domain.clientrelease.model.PackageType;
import com.gvchat.im.admin.domain.clientrelease.model.ReleaseArtifact;
import com.gvchat.im.admin.domain.clientrelease.model.ReleasePlatform;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import java.net.URI;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Validates immutable artifact metadata before a release can become client-visible. */
@Component
public class ReleaseArtifactValidator {
  private final ClientReleaseProperties properties;

  public ReleaseArtifactValidator(ClientReleaseProperties properties) {
    this.properties = properties;
  }

  public void validate(ReleasePlatform platform, String storeUrl, ReleaseArtifact artifact) {
    validatePackageForPlatform(platform, artifact.packageType());
    if (isStoreArtifact(artifact.packageType())) {
      if (isBlank(storeUrl) || artifact.downloadUrl() != null || artifact.sha256() != null || artifact.sizeBytes() != null) {
        throw invalid("Store artifacts require release storeUrl and cannot contain downloadable binary fields");
      }
      requireHttps(storeUrl);
      return;
    }
    if (isBlank(artifact.downloadUrl()) || !artifact.sha256().matches("[0-9a-f]{64}") || artifact.sizeBytes() == null || artifact.sizeBytes() <= 0) {
      throw invalid("Direct artifacts require an HTTPS download URL, lowercase SHA-256, and positive sizeBytes");
    }
    requireHttps(artifact.downloadUrl());
  }

  private void validatePackageForPlatform(ReleasePlatform platform, PackageType type) {
    boolean allowed = switch (platform) {
      case ANDROID -> type == PackageType.APK || type == PackageType.GOOGLE_PLAY;
      case IOS -> type == PackageType.APP_STORE;
      case WINDOWS -> type == PackageType.MSIX || type == PackageType.EXE;
      case MACOS -> type == PackageType.DMG || type == PackageType.PKG;
      case LINUX -> type == PackageType.FLATPAK || type == PackageType.APPIMAGE;
    };
    if (!allowed) throw invalid("packageType is not supported by platform");
  }

  private void requireHttps(String rawUrl) {
    try {
      URI uri = URI.create(rawUrl);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || isBlank(uri.getHost())) throw invalid("URL must be HTTPS");
      if (properties.getAllowedDownloadHosts().isEmpty() || !properties.getAllowedDownloadHosts().contains(uri.getHost().toLowerCase(Locale.ROOT))) {
        throw invalid("CLIENT_RELEASE_DOWNLOAD_HOST_NOT_ALLOWED", "Distribution host is not approved");
      }
    } catch (IllegalArgumentException exception) {
      throw invalid("URL is invalid");
    }
  }

  private boolean isStoreArtifact(PackageType type) { return type == PackageType.GOOGLE_PLAY || type == PackageType.APP_STORE; }
  private boolean isBlank(String value) { return value == null || value.isBlank(); }
  private ApiException invalid(String message) { return invalid("CLIENT_RELEASE_INVALID_REQUEST", message); }
  private ApiException invalid(String code, String message) { return new ApiException(HttpStatusCodes.BAD_REQUEST, code, message); }
}
