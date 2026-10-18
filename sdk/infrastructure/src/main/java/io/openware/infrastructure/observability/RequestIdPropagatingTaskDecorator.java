package io.openware.infrastructure.observability;

import java.util.UUID;
import org.springframework.core.task.TaskDecorator;

/**
 * 异步任务关联标识：把提交线程的 requestId 传给异步线程。
 *
 * <p>与 {@link RequestIdTaskDecorator} 的区别：调度任务是“一次新的 job”，应生成新的 job id；
 * 异步任务通常是某个 HTTP/MQ 请求的子任务，必须继承父 requestId，才能把日志串在同一条链路上。
 */
public class RequestIdPropagatingTaskDecorator implements TaskDecorator {

  static final String ASYNC_PREFIX = "async-";

  @Override
  public Runnable decorate(Runnable runnable) {
    String parent = RequestIdContext.get();
    String fallback = ASYNC_PREFIX + UUID.randomUUID();
    return () -> {
      String previous = RequestIdContext.get();
      RequestIdContext.set(parent == null || parent.isBlank() ? fallback : parent);
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
