package com.gvchat.im.admin.application.clientrelease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gvchat.im.admin.domain.clientrelease.model.ReleaseChannel;
import com.gvchat.im.admin.domain.clientrelease.model.ReleasePlatform;
import com.gvchat.im.admin.domain.clientrelease.model.TargetArchitecture;
import com.gvchat.im.admin.domain.clientrelease.repository.ClientReleasePolicyRepository;
import com.gvchat.im.admin.domain.clientrelease.repository.ClientReleaseRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientReleaseCheckApplicationServiceTest {
  @Test
  void returnsNoReleaseWhenStableChannelHasNoPublishedBaseline() {
    ClientReleaseRepository releaseRepository = mock(ClientReleaseRepository.class);
    ClientReleasePolicyRepository policyRepository = mock(ClientReleasePolicyRepository.class);
    when(releaseRepository.findEligible(ReleasePlatform.ANDROID, ReleaseChannel.STABLE, TargetArchitecture.UNIVERSAL))
        .thenReturn(List.of());
    ClientReleaseCheckApplicationService service = new ClientReleaseCheckApplicationService(
        releaseRepository, policyRepository, new ClientReleaseProperties());

    ClientReleaseCheckApplicationService.CheckResult result = service.check(
        ReleasePlatform.ANDROID, ReleaseChannel.STABLE, 1L, TargetArchitecture.UNIVERSAL, "v1", "installation-id");

    assertEquals("no_release", result.decision());
    assertEquals(null, result.blockReason());
  }
}
