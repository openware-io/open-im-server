package io.openware.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 证明 Spring 调度器 Bean 本身挂了装饰器，而不是只有 decorator 单测通过。
 */
class SchedulingCorrelationConfigTest {

  @Test
  void schedulerBeanRunsTasksWithAJobRequestId() throws Exception {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(SchedulingCorrelationConfig.class)) {
      ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);
      CountDownLatch done = new CountDownLatch(1);
      AtomicReference<String> seen = new AtomicReference<>();

      scheduler.execute(() -> {
        seen.set(RequestIdContext.get());
        done.countDown();
      });

      assertTrue(done.await(5, TimeUnit.SECONDS), "scheduled task did not run");
      assertThat(seen.get()).startsWith(RequestIdTaskDecorator.JOB_PREFIX);
      assertThat(RequestIdContext.get()).isNull();
    }
  }
}
