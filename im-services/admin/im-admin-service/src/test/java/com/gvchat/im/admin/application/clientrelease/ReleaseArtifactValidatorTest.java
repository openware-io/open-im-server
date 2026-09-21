package com.gvchat.im.admin.application.clientrelease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gvchat.im.admin.domain.clientrelease.model.PackageType;
import com.gvchat.im.admin.domain.clientrelease.model.ReleaseArtifact;
import com.gvchat.im.admin.domain.clientrelease.model.ReleasePlatform;
import com.gvchat.im.admin.domain.clientrelease.model.TargetArchitecture;
import com.gvchat.common.exception.ApiException;
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
