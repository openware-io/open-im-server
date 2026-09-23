package io.openware.im.user.infra.scheduler;

import io.openware.im.user.application.cancellation.AccountCancellationProcessor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 账号注销清扫任务：周期性处理待注销申请（硬删账号与关联数据并广播数据擦除事件）。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccountCancellationSweepJob {
  private static final int SWEEP_BATCH = 100;

  private final AccountCancellationProcessor processor;

  @Scheduled(fixedDelayString = "${im.user.account-cancellation.sweep-delay-ms:5000}")
  public void sweep() {
    try {
      List<Long> pendingIds = processor.findPendingIds(SWEEP_BATCH);
      for (Long id : pendingIds) {
        try {
          processor.processOne(id);
        } catch (RuntimeException exception) {
          log.error("Account cancellation processing failed, cancellationId={}", id, exception);
          processor.markFailed(id, exception.getMessage());
        }
      }
      if (!pendingIds.isEmpty()) {
        log.info("Account cancellation sweep processed {} application(s)", pendingIds.size());
      }
    } catch (RuntimeException exception) {
      log.error("Account cancellation sweep failed", exception);
    }
  }
}
