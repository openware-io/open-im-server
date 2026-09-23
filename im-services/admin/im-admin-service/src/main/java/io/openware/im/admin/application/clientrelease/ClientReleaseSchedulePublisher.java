package io.openware.im.admin.application.clientrelease;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ClientReleaseSchedulePublisher {
  private final ClientReleaseApplicationService releaseService;

  @Scheduled(fixedDelayString = "${client-release.schedule-fixed-delay-ms:30000}")
  public void publishDueReleases() {
    releaseService.publishScheduledDue();
  }
}
