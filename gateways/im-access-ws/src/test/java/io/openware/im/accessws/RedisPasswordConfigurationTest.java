package io.openware.im.accessws;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RedisPasswordConfigurationTest {
  @Test
  void shouldConfigureRedisPasswordFromEnvironmentWithoutFallback() throws IOException {
    Path applicationYaml = Path.of("src/main/resources/application.yml");
    String content = Files.readString(applicationYaml);

    assertTrue(content.contains("   password: ${REDIS_PASSWORD:}"));
  }
}
