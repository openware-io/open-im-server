package io.openware.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 验证 Boot 自动配置的异步执行器应用了 TaskDecorator：异步线程能看到父 requestId。
 */
@SpringBootTest(classes = AsyncRequestIdPropagationTest.TestConfig.class)
class AsyncRequestIdPropagationTest {

  @Autowired
  private AsyncProbe probe;

  @BeforeEach
  void setUp() {
    RequestIdContext.set("req-http");
  }

  @AfterEach
  void tearDown() {
    RequestIdContext.clear();
  }

  @Test
  void asyncTaskInheritsParentRequestId() throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<String> seen = new AtomicReference<>();

    probe.capture(seen, done);

    assertTrue(done.await(5, TimeUnit.SECONDS), "async task did not run");
    assertThat(seen.get()).isEqualTo("req-http");
    assertThat(RequestIdContext.get()).isEqualTo("req-http");
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EnableAsync
  @Import(SchedulingCorrelationConfig.class)
  static class TestConfig {
    @Bean
    AsyncProbe asyncProbe() {
      return new AsyncProbe();
    }
  }

  static class AsyncProbe {
    @Async
    public void capture(AtomicReference<String> seen, CountDownLatch done) {
      seen.set(RequestIdContext.get());
      done.countDown();
    }
  }
}
