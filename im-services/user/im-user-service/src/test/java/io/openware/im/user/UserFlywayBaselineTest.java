package io.openware.im.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 基线重建（批次 2）后，用户域只有一个 Flyway 迁移 {@code V1__user_baseline.sql}；
 * 本测试钉住它的表清单与 DC-11 审计列要求。
 */
class UserFlywayBaselineTest {
  private static final String BASELINE = "src/main/resources/db/migration/V1__user_baseline.sql";
  private static final Pattern CREATE_TABLE =
      Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`([^`]+)`", Pattern.CASE_INSENSITIVE);

  @Test
  void shouldCreateOnlyUserDomainTables() throws IOException {
    String migration = Files.readString(Path.of(BASELINE));
    Set<String> expectedTables =
        Set.of(
            "user",
            "user_device_token",
            "user_friend_request",
            "user_friend",
            "user_sticker",
            "user_sticker_quota",
            "user_outbox",
            "user_admin_status_operation",
            "user_device_key",
            "user_notification_setting",
            "user_privacy_setting",
            "user_account_cancellation",
            "user_account_cancellation_log",
            "user_security_question",
            "user_device_session",
            // open_ 前缀属挂起的架构决策，本模块保持表名不变（不重命名、不登记新前缀）。
            "open_application",
            "open_application_scope",
            "open_user_authorization",
            "user_oidc_client",
            "user_oidc_redirect_uri",
            "user_oidc_scope",
            "user_oidc_consent",
            "user_oidc_token_family");

    Matcher matcher = CREATE_TABLE.matcher(migration);
    Set<String> actualTables = new HashSet<>();
    while (matcher.find()) {
      actualTables.add(matcher.group(1));
    }

    assertEquals(expectedTables, actualTables);
    assertTrue(actualTables.stream().allMatch(table -> !table.endsWith("s")));
    // 被删除的积分/代币表只允许出现在头部的说明注释里，不得再被创建。
    assertFalse(migration.contains("CREATE TABLE `user_point_account`"), "user_point_account must be gone");
    assertFalse(migration.contains("CREATE TABLE `user_point_ledger`"), "user_point_ledger must be gone");
    assertFalse(migration.contains("CREATE TABLE `user_coin_account`"), "user_coin_account must be gone");
    assertFalse(migration.contains("CREATE TABLE `user_coin_ledger`"), "user_coin_ledger must be gone");
    assertFalse(migration.contains("TypeORM synchronize"));
    // user 表累计态（V1 + V2 + V6 + V7）。
    assertTrue(migration.contains("`status_version` bigint unsigned NOT NULL DEFAULT '1' COMMENT '用户状态版本'"));
    assertTrue(migration.contains("`phone` varchar(30) DEFAULT NULL COMMENT '手机号'"));
    assertTrue(migration.contains("`self_destruct_policy` varchar(16) NOT NULL DEFAULT 'off'"));
    assertTrue(migration.contains("`status` enum('active','muted','disabled') NOT NULL DEFAULT 'active'"));
    // user_outbox（本模块仅存的待发布事件表）。
    assertTrue(migration.contains("`aggregate_type` varchar(64) NOT NULL COMMENT '聚合类型'"));
    assertTrue(migration.contains("`sharding_key` varchar(128) NOT NULL COMMENT '顺序分片键'"));
    assertTrue(migration.contains("`payload_json` json NOT NULL COMMENT '事件载荷'"));
    assertTrue(migration.contains("`uk_user_outbox_event` (`event_id`)"));
    // open_application 累计态（V11 + V16 + V17）。
    assertTrue(migration.contains("`app_secret_hash` varchar(64) NULL COMMENT '应用密钥 SHA-256 哈希（审核通过后分配）'"));
    assertTrue(migration.contains("`subject_name` varchar(128) NULL COMMENT '主体名称（接入方/业务方）'"));
    assertTrue(migration.contains("`reject_reason` varchar(512) NULL COMMENT '驳回原因'"));
  }

  /** DC-11：每张表都要有 4 个审计列，且 created_at / updated_at 是 datetime(3)。 */
  @Test
  void shouldCarryAllFourAuditColumnsInEveryTable() throws IOException {
    String migration = Files.readString(Path.of(BASELINE));
    for (String table : tableDefinitions(migration).keySet()) {
      String definition = tableDefinitions(migration).get(table);
      for (String audit : new String[] {"created_by", "created_at", "updated_by", "updated_at"}) {
        assertTrue(definition.contains("`" + audit + "`"),
            "table " + table + " is missing audit column " + audit);
      }
      for (String timeColumn : new String[] {"created_at", "updated_at"}) {
        assertTrue(Pattern.compile("`" + timeColumn + "`\\s+datetime\\(3\\)").matcher(definition).find(),
            "table " + table + " column " + timeColumn + " is not datetime(3)");
      }
      assertTrue(definition.contains("`created_by` bigint unsigned NOT NULL DEFAULT 0")
              || definition.contains("`created_by` bigint unsigned NOT NULL DEFAULT '0'"),
          "table " + table + " created_by must be NOT NULL DEFAULT 0");
      assertTrue(definition.contains("`updated_by` bigint unsigned NOT NULL DEFAULT 0")
              || definition.contains("`updated_by` bigint unsigned NOT NULL DEFAULT '0'"),
          "table " + table + " updated_by must be NOT NULL DEFAULT 0");
    }
  }

  /** 每张表的 PK 必须是 `id` bigint unsigned AUTO_INCREMENT（业务键降为唯一键）。 */
  @Test
  void shouldUseAutoIncrementIdPrimaryKeyEverywhere() throws IOException {
    String migration = Files.readString(Path.of(BASELINE));
    for (String table : tableDefinitions(migration).keySet()) {
      String definition = tableDefinitions(migration).get(table);
      assertTrue(definition.contains("`id` bigint unsigned NOT NULL AUTO_INCREMENT"),
          "table " + table + " has no auto-increment id");
      assertTrue(definition.contains("PRIMARY KEY (`id`)"), "table " + table + " PK is not (id)");
    }
  }

  @Test
  void shouldRecognizeCreateTableIfNotExistsSyntax() {
    Matcher matcher = CREATE_TABLE.matcher("CREATE TABLE IF NOT EXISTS `messages` (id BIGINT)");

    assertTrue(matcher.find());
    assertTrue("messages".equals(matcher.group(1)));
  }

  /**
   * 默认管理员（admin/888999）的固定口令种子已彻底移除：基线不植入该行，也不再携带其哈希，
   * 首管理员改由运维通过受管流程创建并强制首登改密。
   */
  @Test
  void shouldNotSeedDefaultAdminCredential() throws IOException {
    String migration = Files.readString(Path.of(BASELINE));
    assertFalse(migration.contains("$2a$10$"));
    assertFalse(Pattern.compile("INSERT\\s+INTO\\s+`user`", Pattern.CASE_INSENSITIVE).matcher(migration).find());
  }

  private static java.util.Map<String, String> tableDefinitions(String migration) {
    java.util.Map<String, String> definitions = new java.util.LinkedHashMap<>();
    Matcher matcher = Pattern.compile("CREATE TABLE `([^`]+)`").matcher(migration);
    while (matcher.find()) {
      int end = migration.indexOf("\n) ENGINE=InnoDB", matcher.start());
      assertTrue(end > 0, "unterminated table definition: " + matcher.group(1));
      definitions.put(matcher.group(1), migration.substring(matcher.start(), end));
    }
    return definitions;
  }
}
