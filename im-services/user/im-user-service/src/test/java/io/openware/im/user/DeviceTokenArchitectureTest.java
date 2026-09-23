package io.openware.im.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DeviceTokenArchitectureTest {
  @Test
  void shouldKeepDeviceDomainFreeOfFrameworkDependencies() throws IOException {
    String model = Files.readString(
        Path.of("src/main/java/io/openware/im/user/domain/device/model/DeviceToken.java"));
    String repository = Files.readString(
        Path.of("src/main/java/io/openware/im/user/domain/device/repository/DeviceTokenRepository.java"));
    String adapter = Files.readString(Path.of(
        "src/main/java/io/openware/im/user/infra/persistence/device/repository/DeviceTokenRepositoryAdapter.java"));

    assertFalse(model.contains("org.springframework"));
    assertFalse(model.contains("com.baomidou"));
    assertFalse(repository.contains("org.springframework"));
    assertFalse(repository.contains("com.baomidou"));
    assertTrue(adapter.contains("com.baomidou"));
  }
}
