package com.gvchat.im.conversation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ConversationLayerArchitectureTest {
  private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "com", "gvchat", "im", "conversation");

  @Test
  void coreLayersMustRemainPresent() {
    assertTrue(Files.isDirectory(SOURCE_ROOT.resolve("api")));
    assertTrue(Files.isDirectory(SOURCE_ROOT.resolve("application")));
    assertTrue(Files.isDirectory(SOURCE_ROOT.resolve("domain")));
    assertTrue(Files.isDirectory(SOURCE_ROOT.resolve("infra")));
  }

  @Test
  void refactoredSourceMustNotDependOnHistoricalRootPackages() throws IOException {
    List<String> imports = importsUnder(SOURCE_ROOT);

    assertFalse(imports.stream().anyMatch(value -> value.startsWith("com.gvchat.common.dto.")));
    assertFalse(imports.stream().anyMatch(value -> value.startsWith("com.gvchat.im.domain.")));
  }

  @Test
  void domainMustNotDependOnOuterLayersOrFrameworkImplementations() throws IOException {
    List<String> imports = importsUnder(SOURCE_ROOT.resolve("domain"));

    assertFalse(imports.stream().anyMatch(value -> value.startsWith("com.gvchat.im.conversation.api.")));
    assertFalse(imports.stream().anyMatch(value -> value.startsWith("com.gvchat.im.conversation.application.")));
    assertFalse(imports.stream().anyMatch(value -> value.startsWith("com.gvchat.im.conversation.infra.")));
    assertFalse(imports.stream().anyMatch(this::isForbiddenDomainFrameworkImport));
  }

  @Test
  void messageImplementationsMustNotBeImported() throws IOException {
    List<String> imports = importsUnder(SOURCE_ROOT);

    assertFalse(imports.stream().anyMatch(value -> value.startsWith("com.gvchat.im.message.")
        && !value.startsWith("com.gvchat.im.message.api.")));
  }

  private List<String> importsUnder(Path root) throws IOException {
    try (Stream<Path> paths = Files.walk(root)) {
      return paths.filter(path -> path.toString().endsWith(".java"))
          .flatMap(this::readImports)
          .toList();
    }
  }

  private Stream<String> readImports(Path path) {
    try {
      return Files.readAllLines(path).stream()
          .map(String::trim)
          .filter(line -> line.startsWith("import "))
          .map(line -> line.substring("import ".length(), line.length() - 1));
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot read source file: " + path, exception);
    }
  }

  private boolean isForbiddenDomainFrameworkImport(String value) {
    return value.startsWith("org.springframework.")
        || value.startsWith("com.baomidou.")
        || value.startsWith("com.fasterxml.jackson.")
        || value.startsWith("org.apache.rocketmq.")
        || value.startsWith("org.springframework.data.");
  }
}
