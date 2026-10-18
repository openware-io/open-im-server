package io.openware.infrastructure.observability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
}
