package com.gvchat.im.message.scheduler;

import com.gvchat.im.message.application.secretmessage.SecretMessageApplicationService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 私密消息定时销毁调度器：周期扫描已到销毁时间的密文消息并标记销毁。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SecretMessageDestroyScheduler {
  private final SecretMessageApplicationService secretMessageService;

  @Scheduled(fixedDelayString = "${im.message.secret.destroy-scan-delay-ms:5000}")
  public void destroyExpiredMessages() {
    try {
      secretMessageService.destroyExpired(LocalDateTime.now(Clock.systemUTC()), 200);
    } catch (RuntimeException e) {
      log.warn("Secret message destroy scan failed", e);
    }
  }
}
