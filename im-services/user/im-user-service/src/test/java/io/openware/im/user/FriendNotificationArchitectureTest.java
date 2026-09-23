package io.openware.im.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FriendNotificationArchitectureTest {
  @Test
  void shouldKeepFriendDomainFreeOfInfrastructureDependencies() throws IOException {
    String source = Files.readString(Path.of(
        "src/main/java/io/openware/im/user/domain/social/port/FriendEventOutbox.java"));
    String publisher = Files.readString(
        Path.of("src/main/java/io/openware/im/user/infra/messaging/outbox/OutboxFriendEventAppender.java"));

    assertFalse(source.contains("org.springframework"));
    assertFalse(source.contains("com.baomidou"));
    assertTrue(source.contains("FriendRequested"));
    assertTrue(publisher.contains("UserOutboxMapper"));
    assertFalse(publisher.contains("MqProducer"));
  }
}
