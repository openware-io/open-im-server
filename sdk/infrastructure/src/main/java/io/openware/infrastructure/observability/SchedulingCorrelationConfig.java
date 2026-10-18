package io.openware.infrastructure.observability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 为调度线程池注册 requestId 装饰器。
 *
 * <p>所有启用 {@code @EnableScheduling} 的服务通过 {@code @Import} 获得同一个调度器：
 * 每次调度执行由 {@link RequestIdTaskDecorator} 生成 job requestId，执行完清理。
 */
@Configuration
public class SchedulingCorrelationConfig {

  @Bean
  public ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("scheduled-");
    scheduler.setTaskDecorator(new RequestIdTaskDecorator());
    scheduler.initialize();
    return scheduler;
  }

  /** Spring Boot 的自动配置异步执行器会应用这个 TaskDecorator。 */
  @Bean
  public RequestIdPropagatingTaskDecorator requestIdPropagatingTaskDecorator() {
    return new RequestIdPropagatingTaskDecorator();
  }

  /**
   * 独立的异步执行器：不能复用调度器的 {@code ThreadPoolTaskScheduler}，
   * 否则 {@code @Async} 会拿到调度器的 job requestId，且与定时任务争抢同一个 4 线程池。
   */
  @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
  public ThreadPoolTaskExecutor applicationTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(200);
    executor.setThreadNamePrefix("async-");
    executor.setTaskDecorator(new RequestIdPropagatingTaskDecorator());
    executor.initialize();
    return executor;
  }
}
