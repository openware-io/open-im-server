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
  @Test
  void returnsStableCodeForUnapprovedDownloadHost() {
    ClientReleaseProperties properties = new ClientReleaseProperties();
    properties.setAllowedDownloadHosts(Set.of("downloads.example.com"));
    ReleaseArtifact artifact = new ReleaseArtifact(null, null, TargetArchitecture.UNIVERSAL, PackageType.APK,
        "https://unapproved.example.com/app.apk", "a".repeat(64), 1L, null);

    ApiException exception = assertThrows(ApiException.class,
        () -> new ReleaseArtifactValidator(properties).validate(ReleasePlatform.ANDROID, null, artifact));

    assertEquals("CLIENT_RELEASE_DOWNLOAD_HOST_NOT_ALLOWED", exception.getCode());
  }
}
