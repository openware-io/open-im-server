package io.openware.infrastructure.observability;

import java.util.UUID;
import org.springframework.core.task.TaskDecorator;

/**
 * 定时任务关联标识（standards/40）：每次调度执行生成一个 job requestId。
 *
 * <p>Spring Boot 的调度线程池会应用容器中的 {@link TaskDecorator}。
 * 这样 15+ 个 {@code @Scheduled} 方法不必逐个改造，也能让每次执行拥有可检索的关联标识；
 * 执行结束恢复外层上下文，避免线程复用串味。
 */
public class RequestIdTaskDecorator implements TaskDecorator {

  static final String JOB_PREFIX = "job-";

  @Override
  public Runnable decorate(Runnable runnable) {
    String previous = RequestIdContext.get();
    String jobRequestId = JOB_PREFIX + UUID.randomUUID();
    return () -> {
      RequestIdContext.set(jobRequestId);
      try {
        runnable.run();
      } finally {
        if (previous == null || previous.isBlank()) {
          RequestIdContext.clear();
        } else {
          RequestIdContext.set(previous);
        }
      }
    };
  }
}
