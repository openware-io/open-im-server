package io.openware.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RequestIdTaskDecoratorTest {

  private final RequestIdTaskDecorator decorator = new RequestIdTaskDecorator();

  @AfterEach
  void clear() {
    RequestIdContext.clear();
  }

  @Test
  void setsAJobRequestIdAndClearsItAfterRun() {
    AtomicReference<String> seen = new AtomicReference<>();

    decorator.decorate(() -> seen.set(RequestIdContext.get())).run();

    assertThat(seen.get()).startsWith("job-");
    assertThat(RequestIdContext.get()).isNull();
  }

  @Test
  void restoresTheOuterRequestIdAfterRun() {
    RequestIdContext.set("req-outer");
    AtomicReference<String> seen = new AtomicReference<>();

    decorator.decorate(() -> seen.set(RequestIdContext.get())).run();

    assertThat(seen.get()).startsWith("job-").isNotEqualTo("req-outer");
    assertThat(RequestIdContext.get()).isEqualTo("req-outer");
  }
}
