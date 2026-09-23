package io.openware.im.message;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MqGroupConfigurationTest {
  @Test
  void messageServiceUsesItsDedicatedProducerGroupVariable() throws IOException {
    String yaml = Files.readString(
        Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);

    assertTrue(yaml.contains("${IM_MESSAGE_SERVICE_MQ_PRODUCER_GROUP:im-message-service-producer}"));
    assertFalse(yaml.contains("IM_MQ_PRODUCER_GROUP"));
  }
}
