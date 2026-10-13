package io.openware.im.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlywayConfigurationTest {
  @Test
  void shouldBaselineSharedSchemaBeforeRunningMessageMigrations() throws IOException {
    String applicationYaml = Files.readString(Path.of("src/main/resources/application.yml"));

    assertTrue(applicationYaml.contains("baseline-on-migrate: true"));
    assertTrue(applicationYaml.contains("baseline-version: 0"));
    assertTrue(applicationYaml.contains("table: msg_schema_history"));
  }

  @Test
  void shouldContainAuthoritativeMessageConstraintsInSingleBaseline() throws IOException {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V1__msg_baseline.sql"));

    assertTrue(migration.contains("msg_conversation_sequence"));
    assertTrue(migration.contains("uk_msg_message_conversation_seq"));
    assertTrue(migration.contains("msg_user_sync_sequence"));
    assertTrue(migration.contains("msg_user_sync_index"));
    assertTrue(!migration.contains("IF NOT EXISTS"));
    // 基线重建（批次 2）：原 13 个迁移文件已合并为唯一的 V1，后续变更从 V2 起。
    assertTrue(!Files.exists(Path.of("src/main/resources/db/migration/V2__add_msg_read_status_sync_index.sql")));
    try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
      assertEquals(List.of("V1__msg_baseline.sql"),
          files.map(path -> path.getFileName().toString()).sorted().toList());
    }
  }

  @Test
  void shouldConfigureMongoAuthenticationFromDedicatedEnvironmentVariables() throws IOException {
    String applicationYaml = Files.readString(Path.of("src/main/resources/application.yml"));

    assertTrue(applicationYaml.contains("mongodb:"));
    assertTrue(applicationYaml.contains("host: ${MONGODB_HOST:localhost}"));
    assertTrue(applicationYaml.contains("username: ${MONGODB_USERNAME:}"));
    assertTrue(applicationYaml.contains("password: ${MONGODB_PASSWORD:}"));
    assertTrue(applicationYaml.contains("authentication-database: ${MONGODB_AUTH_DATABASE:admin}"));
  }
}
