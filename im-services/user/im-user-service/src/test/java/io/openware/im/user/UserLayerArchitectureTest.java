package io.openware.im.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class UserLayerArchitectureTest {
  private static final Path SOURCE_ROOT = Path.of("src/main/java/io/openware/im/user");
  private static final List<String> DOMAIN_FORBIDDEN_DEPENDENCIES = List.of(
      "org.springframework", "com.baomidou", "org.apache.ibatis", "com.fasterxml.jackson",
      "org.springframework.data.redis", "org.apache.rocketmq", ".infra.");
  private static final List<String> API_FORBIDDEN_DEPENDENCIES = List.of(
      ".domain.repository.", ".infra.", ".persistence.", "BaseMapper", "@Mapper");

  @Test
  void shouldKeepDomainFreeOfFrameworkAndInfrastructureDependencies() throws IOException {
    List<String> sources = readSources(SOURCE_ROOT.resolve("domain"));

    assertFalse(sources.isEmpty());
    for (String source : sources) {
      for (String forbiddenDependency : DOMAIN_FORBIDDEN_DEPENDENCIES) {
        assertFalse(source.contains(forbiddenDependency), forbiddenDependency);
      }
    }
  }

  @Test
  void shouldKeepApiFreeOfRepositoriesAndInfrastructureDependencies() throws IOException {
    List<String> sources = readSources(SOURCE_ROOT.resolve("api"));

    assertFalse(sources.isEmpty());
    assertTrue(sources.stream().anyMatch(source -> source.contains("@RestController")));
    for (String source : sources) {
      for (String forbiddenDependency : API_FORBIDDEN_DEPENDENCIES) {
        assertFalse(source.contains(forbiddenDependency), forbiddenDependency);
      }
    }
  }

  @Test
  void shouldKeepApplicationFreeOfInfrastructureImplementations() throws IOException {
    List<String> sources = readSources(SOURCE_ROOT.resolve("application"));

    assertFalse(sources.stream().anyMatch(source -> source.contains("io.openware.infrastructure.")));
  }

  private List<String> readSources(Path root) throws IOException {
    try (Stream<Path> paths = Files.walk(root)) {
      return paths.filter(path -> path.toString().endsWith(".java"))
          .map(this::readSource)
          .toList();
    }
  }

  private String readSource(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
