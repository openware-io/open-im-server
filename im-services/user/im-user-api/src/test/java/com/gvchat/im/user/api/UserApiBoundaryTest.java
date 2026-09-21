package com.gvchat.im.user.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class UserApiBoundaryTest {
  @Test
  void shouldExposeApiContractsWithoutPersistenceOrSpringComponents() throws IOException {
    Path sourceRoot = Path.of("src/main/java");

    assertTrue(Files.exists(sourceRoot.resolve("com/gvchat/im/user/api/friend/FriendshipView.java")));
    try (Stream<Path> files = Files.walk(sourceRoot)) {
      String source = files
          .filter(path -> path.toString().endsWith(".java"))
          .map(this::read)
          .reduce("", String::concat);
      assertFalse(source.contains("@TableName"));
      assertFalse(source.contains("@Mapper"));
      assertFalse(source.contains("@Service"));
      assertFalse(source.contains("@RestController"));
      assertFalse(source.contains("BaseMapper"));
    }
  }

  private String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
