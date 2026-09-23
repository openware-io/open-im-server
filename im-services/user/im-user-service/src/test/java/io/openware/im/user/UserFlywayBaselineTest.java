package io.openware.im.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class UserFlywayBaselineTest {
  private static final Pattern CREATE_TABLE =
      Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`([^`]+)`", Pattern.CASE_INSENSITIVE);

  @Test
  void shouldCreateOnlyUserDomainTables() throws IOException {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V1__init.sql"));
    Set<String> expectedTables =
        Set.of(
            "user",
            "user_device_token",
            "user_friend_request",
            "user_friend",
            "user_point_account",
            "user_point_ledger",
            "user_sticker",
            "user_sticker_quota",
            "user_outbox",
            "user_admin_status_operation");

    Matcher matcher = CREATE_TABLE.matcher(migration);
    Set<String> actualTables = new java.util.HashSet<>();
    while (matcher.find()) {
      actualTables.add(matcher.group(1));
    }

    assertTrue(actualTables.containsAll(expectedTables));
    assertTrue(actualTables.stream().allMatch(expectedTables::contains));
    assertTrue(actualTables.stream().allMatch(table -> !table.endsWith("s")));
    Set<String> aggregateTables = new java.util.HashSet<>(actualTables);
    aggregateTables.remove("user_sticker_quota");
    aggregateTables.remove("user_admin_status_operation");
    assertTrue(aggregateTables.stream().allMatch(table -> {
      int tableStart = migration.indexOf("CREATE TABLE `" + table + "`");
      int tableEnd = migration.indexOf(";", tableStart);
      String definition = migration.substring(tableStart, tableEnd);
      return definition.contains("`id`")
          && definition.contains("`created_by`")
          && definition.contains("`created_at`")
          && definition.contains("`updated_by`")
          && definition.contains("`updated_at`");
    }));
    assertFalse(migration.contains("TypeORM synchronize"));
    assertTrue(migration.contains("`status_version` bigint unsigned NOT NULL DEFAULT '1' COMMENT '用户状态版本'"));
    assertTrue(migration.contains("`business_type` varchar(64)"));
    assertTrue(migration.contains("`business_order_no` varchar(64)"));
    assertTrue(migration.contains("`command_id` varchar(64)"));
    assertTrue(migration.contains("`operator_user_id` bigint unsigned"));
    assertTrue(migration.contains("`uk_user_point_ledger_command_id`"));
    assertFalse(Files.exists(Path.of("src/main/resources/db/migration/V2__point_ledger_business_idempotency.sql")));
  }

  @Test
  void shouldRecognizeCreateTableIfNotExistsSyntax() {
    Matcher matcher = CREATE_TABLE.matcher("CREATE TABLE IF NOT EXISTS `messages` (id BIGINT)");

    assertTrue(matcher.find());
    assertTrue("messages".equals(matcher.group(1)));
  }

  @Test
  void shouldNeutralizeDefaultAdminCredential() throws IOException {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V8__remove_default_admin_seed.sql"));
    assertTrue(migration.contains("DELETE FROM `user`"));
    assertTrue(migration.contains("`username` = 'admin'"));
    assertTrue(migration.contains("$2a$10$"));
  }
}
