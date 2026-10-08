package io.openware.im.admin.application.clientrelease;

import io.openware.im.admin.domain.clientrelease.model.PackageType;
import io.openware.im.admin.domain.clientrelease.model.ReleaseArtifact;
import io.openware.im.admin.domain.clientrelease.model.ReleasePlatform;
import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
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
    requireTrustedUrl(artifact.downloadUrl());
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
    requireTrustedUrl(rawUrl, false);
  }

  private void requireTrustedUrl(String rawUrl) {
    requireTrustedUrl(rawUrl, properties.isAllowInsecureLocalDownloads());
  }

  private void requireTrustedUrl(String rawUrl, boolean allowInsecureLocal) {
    try {
      URI uri = URI.create(rawUrl);
      String host = uri.getHost();
      boolean https = "https".equalsIgnoreCase(uri.getScheme());
      boolean permittedLocalHttp = allowInsecureLocal && "http".equalsIgnoreCase(uri.getScheme()) && isLocalHost(host);
      if (isBlank(host) || (!https && !permittedLocalHttp)) throw invalid("URL must be HTTPS");
      if (properties.getAllowedDownloadHosts().isEmpty() || !properties.getAllowedDownloadHosts().contains(host.toLowerCase(Locale.ROOT))) {
        throw invalid("CLIENT_RELEASE_DOWNLOAD_HOST_NOT_ALLOWED", "Distribution host is not approved");
      }
    } catch (IllegalArgumentException exception) {
      throw invalid("URL is invalid");
    }
  }

  private boolean isLocalHost(String host) {
    if (isBlank(host)) return false;
    String normalized = host.toLowerCase(Locale.ROOT);
    if (normalized.equals("localhost") || normalized.equals("::1")) return true;
    String[] octets = normalized.split("\\.", -1);
    if (octets.length != 4) return normalized.startsWith("fc") || normalized.startsWith("fd") || normalized.startsWith("fe8") || normalized.startsWith("fe9") || normalized.startsWith("fea") || normalized.startsWith("feb");
    try {
      int first = Integer.parseInt(octets[0]);
      int second = Integer.parseInt(octets[1]);
      for (String octet : octets) if (Integer.parseInt(octet) > 255) return false;
      return first == 10 || first == 127 || (first == 172 && second >= 16 && second <= 31) || (first == 192 && second == 168) || (first == 169 && second == 254);
    } catch (NumberFormatException exception) {
      return false;
    }
  }

  private boolean isStoreArtifact(PackageType type) { return type == PackageType.GOOGLE_PLAY || type == PackageType.APP_STORE; }
  private boolean isBlank(String value) { return value == null || value.isBlank(); }
  private ApiException invalid(String message) { return invalid("CLIENT_RELEASE_INVALID_REQUEST", message); }
  private ApiException invalid(String code, String message) { return new ApiException(HttpStatusCodes.BAD_REQUEST, code, message); }
}
