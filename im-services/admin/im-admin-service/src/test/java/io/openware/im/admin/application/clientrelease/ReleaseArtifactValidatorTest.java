package io.openware.im.admin.application.clientrelease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.openware.im.admin.domain.clientrelease.model.PackageType;
import io.openware.im.admin.domain.clientrelease.model.ReleaseArtifact;
import io.openware.im.admin.domain.clientrelease.model.ReleasePlatform;
import io.openware.im.admin.domain.clientrelease.model.TargetArchitecture;
import io.openware.common.exception.ApiException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReleaseArtifactValidatorTest {
  private static final String SHA256 = "a".repeat(64);

  @Test
  void returnsStableCodeForUnapprovedDownloadHost() {
    ClientReleaseProperties properties = new ClientReleaseProperties();
    properties.setAllowedDownloadHosts(Set.of("downloads.example.com"));
    ReleaseArtifact artifact = new ReleaseArtifact(null, null, TargetArchitecture.UNIVERSAL, PackageType.APK,
        "https://unapproved.example.com/app.apk", SHA256, 1L, null);

    ApiException exception = assertThrows(ApiException.class,
        () -> new ReleaseArtifactValidator(properties).validate(ReleasePlatform.ANDROID, null, artifact));

    assertEquals("CLIENT_RELEASE_DOWNLOAD_HOST_NOT_ALLOWED", exception.getCode());
  }

  @Test
  void rejectsLocalHttpByDefault() {
    ClientReleaseProperties properties = properties("127.0.0.1", false);
    assertThrows(ApiException.class, () -> validate(properties, "http://127.0.0.1:30900/app.apk"));
  }

  @Test
  void allowsWhitelistedLoopbackHttpWhenExplicitlyEnabled() {
    validate(properties("127.0.0.1", true), "http://127.0.0.1:30900/app.apk");
  }

  @Test
  void allowsWhitelistedPrivateLanHttpWhenExplicitlyEnabled() {
    validate(properties("192.168.3.35", true), "http://192.168.3.35:30900/app.apk");
  }

  @Test
  void rejectsPublicHttpEvenWhenLocalHttpIsEnabled() {
    assertThrows(ApiException.class, () -> validate(properties("downloads.example.com", true), "http://downloads.example.com/app.apk"));
  }

  @Test
  void stillRejectsUnapprovedLocalHostWhenLocalHttpIsEnabled() {
    ApiException exception = assertThrows(ApiException.class,
        () -> validate(properties("127.0.0.1", true), "http://192.168.3.35:30900/app.apk"));
    assertEquals("CLIENT_RELEASE_DOWNLOAD_HOST_NOT_ALLOWED", exception.getCode());
  }

  private ClientReleaseProperties properties(String host, boolean allowInsecureLocalDownloads) {
    ClientReleaseProperties properties = new ClientReleaseProperties();
    properties.setAllowedDownloadHosts(Set.of(host));
    properties.setAllowInsecureLocalDownloads(allowInsecureLocalDownloads);
    return properties;
  }

  private void validate(ClientReleaseProperties properties, String url) {
    ReleaseArtifact artifact = new ReleaseArtifact(null, null, TargetArchitecture.UNIVERSAL, PackageType.APK,
        url, SHA256, 1L, null);
    new ReleaseArtifactValidator(properties).validate(ReleasePlatform.ANDROID, null, artifact);
  }
}
