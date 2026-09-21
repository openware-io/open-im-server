package com.gvchat.im.user.infra.scheduler;

import com.gvchat.im.user.application.account.SelfDestructApplicationService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 账号自毁清扫任务：周期性硬删到期（长期不登录）的账号及其全部数据。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SelfDestructSweepJob {
  private static final int SWEEP_BATCH = 200;

  private final SelfDestructApplicationService selfDestructApplicationService;

  @Scheduled(fixedDelayString = "${im.user.self-destruct.sweep-delay-ms:60000}")
  public void sweep() {
    try {
      int deleted = selfDestructApplicationService.sweepExpired(LocalDateTime.now(), SWEEP_BATCH);
      if (deleted > 0) {
        log.info("Self destruct sweep hard deleted {} account(s)", deleted);
      }
    } catch (RuntimeException ex) {
      log.error("Self destruct sweep failed", ex);
    }
  }
}
