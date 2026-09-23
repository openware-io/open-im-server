package io.openware.im.message.scheduler;

import io.openware.im.message.application.secretgroupmessage.SecretGroupMessageApplicationService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 私密群聊消息定时销毁调度器：周期扫描已到销毁时间的密文消息并逐接收方销毁。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SecretGroupMessageDestroyScheduler {
  private final SecretGroupMessageApplicationService secretGroupMessageService;

  @Scheduled(fixedDelayString = "${im.message.secret.destroy-scan-delay-ms:5000}")
  public void destroyExpiredMessages() {
    try {
      secretGroupMessageService.destroyExpired(LocalDateTime.now(Clock.systemUTC()), 200);
    } catch (RuntimeException e) {
      log.warn("Secret group message destroy scan failed", e);
    }
  }
}
