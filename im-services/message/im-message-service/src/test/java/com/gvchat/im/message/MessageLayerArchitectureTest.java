package com.gvchat.im.message;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MessageLayerArchitectureTest {
  private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "com", "gvchat", "im", "message");
  private static final Path LEGACY_SOURCE_ROOT = Path.of("src", "main", "java", "com", "gvchat", "im");
  private static final List<String> REMOVED_LEGACY_TYPES = List.of(
      "SensitiveWord",
      "ContentFilterService",
      "SensitiveWordRepository",
      "SystemConfig",
      "AppConfigService",
      "SystemConfigRepository");

  @Test
  void apiMustNotDependOnDomainOrInfrastructure() throws IOException {
    List<String> imports = importsUnder("api");

    assertFalse(imports.stream().anyMatch(this::isDomainImport));
    assertFalse(imports.stream().anyMatch(this::isInfrastructureImport));
  }

  @Test
  void applicationMustNotDependOnApiOrInfrastructure() throws IOException {
    List<String> imports = importsUnder("application");

    assertFalse(imports.stream().anyMatch(this::isApiImport));
    assertFalse(imports.stream().anyMatch(this::isInfrastructureImport));
  }

  @Test
  void domainMustNotDependOnOuterLayersOrFrameworkImplementations() throws IOException {
    List<String> imports = importsUnder("domain");

    assertFalse(imports.stream().anyMatch(this::isApiImport));
    assertFalse(imports.stream().anyMatch(this::isApplicationImport));
    assertFalse(imports.stream().anyMatch(this::isInfrastructureImport));
    assertFalse(imports.stream().anyMatch(this::isForbiddenDomainFrameworkImport));
  }

  @Test
  void coreLayersMustRemainPresent() {
    assertTrue(Files.isDirectory(SOURCE_ROOT.resolve("api")));
    assertTrue(Files.isDirectory(SOURCE_ROOT.resolve("application")));
    assertTrue(Files.isDirectory(SOURCE_ROOT.resolve("domain")));
    assertTrue(Files.isDirectory(SOURCE_ROOT.resolve("infra")));
    assertTrue(Files.isDirectory(SOURCE_ROOT.resolve("infra").resolve("projection")));
    assertFalse(Files.exists(SOURCE_ROOT.resolve("projection")));
  }

  @Test
  void refactoredSourceMustNotDependOnHistoricalRootPackages() throws IOException {
    List<String> imports = importsUnderSourceRoot();

    assertFalse(imports.stream().anyMatch(value -> value.startsWith("com.gvchat.common.dto.")));
    assertFalse(imports.stream().anyMatch(value -> value.startsWith("com.gvchat.im.domain.")));
  }

  @Test
  void domainMustNotDependOnConversationOrUserServiceImplementations() throws IOException {
    List<String> imports = importsUnder("domain");

    assertFalse(imports.stream().anyMatch(this::isForeignServiceImplementationImport));
  }

  @Test
  void mustNotRetainRemovedSensitiveWordOrSystemConfigReferences() throws IOException {
    try (Stream<Path> paths = Files.walk(LEGACY_SOURCE_ROOT)) {
      List<String> sourceFiles = paths.filter(path -> path.toString().endsWith(".java"))
          .flatMap(this::readSource)
          .toList();

      assertFalse(sourceFiles.stream()
          .anyMatch(line -> REMOVED_LEGACY_TYPES.stream().anyMatch(line::contains)));
    }
  }

  private List<String> importsUnder(String layer) throws IOException {
    try (Stream<Path> paths = Files.walk(SOURCE_ROOT.resolve(layer))) {
      return paths.filter(path -> path.toString().endsWith(".java"))
          .flatMap(this::readImports)
          .toList();
    }
  }

  private List<String> importsUnderSourceRoot() throws IOException {
    try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
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
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot read source file: " + path, ex);
    }
  }

  private Stream<String> readSource(Path path) {
    try {
      return Files.readAllLines(path).stream();
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot read source file: " + path, ex);
    }
  }

  private boolean isApiImport(String value) {
    return value.startsWith("com.gvchat.im.message.api.");
  }

  private boolean isApplicationImport(String value) {
    return value.startsWith("com.gvchat.im.message.application.");
  }

  private boolean isDomainImport(String value) {
    return value.startsWith("com.gvchat.im.message.domain.");
  }

  private boolean isInfrastructureImport(String value) {
    return value.startsWith("com.gvchat.im.message.infra.");
  }

  private boolean isForbiddenDomainFrameworkImport(String value) {
    return value.startsWith("org.springframework.")
        || value.startsWith("com.baomidou.")
        || value.startsWith("com.fasterxml.jackson.")
        || value.startsWith("org.apache.rocketmq.")
        || value.startsWith("org.springframework.data.");
  }

  private boolean isForeignServiceImplementationImport(String value) {
    return value.startsWith("com.gvchat.im.conversation.") && !value.startsWith("com.gvchat.im.conversation.api.")
        || value.startsWith("com.gvchat.im.user.") && !value.startsWith("com.gvchat.im.user.api.");
  }
}
