package io.openware.common.audit.infra.persistence.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * V3 迁移回归：历史 {@code occurred_at IS NULL} 行必须被回填为 {@code created_at}。
 *
 * <p>不是字符串断言，而是把 {@code V3__audit_occurred_at_backfill.sql} 里的语句在
 * H2（MySQL 兼容模式）上**真跑一遍**：迁移前造一条 NULL 行（复现线上现象）与一条有值行，
 * 迁移后 NULL 行变为非空且等于 created_at，有值行保持原值（回填只影响 NULL 行）。
 */
class AuditOccurredAtMigrationTest {

  private static final String MIGRATION =
      "src/main/resources/db/migration/V3__audit_occurred_at_backfill.sql";

  @Test
  void backfillsHistoricalNullOccurredAtWithCreatedAt() throws Exception {
    List<String> statements = statementsOf(MIGRATION);
    assertTrue(statements.stream().anyMatch(sql -> sql.toLowerCase().contains("occurred_at")
            && sql.toLowerCase().contains("is null")),
        "V3 必须包含 occurred_at IS NULL 的回填语句: " + statements);

    try (Connection connection = DriverManager.getConnection(
        "jdbc:h2:mem:audit-v3;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE `iam_audit_log` ("
          + "`id` bigint NOT NULL PRIMARY KEY,"
          + "`action` varchar(128) NOT NULL,"
          + "`created_at` timestamp(3) NOT NULL,"
          + "`occurred_at` timestamp(3) NULL)");
      LocalDateTime createdAt = LocalDateTime.of(2026, 9, 15, 12, 0);
      LocalDateTime explicit = LocalDateTime.of(2026, 8, 1, 9, 30);
      statement.execute("INSERT INTO `iam_audit_log` VALUES (1, 'order.settle', "
          + "'2026-09-15 12:00:00', NULL)");
      statement.execute("INSERT INTO `iam_audit_log` VALUES (2, 'payment.collect', "
          + "'2026-09-15 12:00:00', '2026-08-01 09:30:00')");

      for (String sql : statements) {
        statement.execute(sql);
      }

      try (ResultSet rows = statement.executeQuery(
          "SELECT id, created_at, occurred_at FROM `iam_audit_log` ORDER BY id")) {
        assertTrue(rows.next());
        assertNotNull(rows.getTimestamp("occurred_at"), "历史 NULL 行迁移后 occurred_at 必须非空");
        assertEquals(Timestamp.valueOf(createdAt), rows.getTimestamp("occurred_at"));
        assertTrue(rows.next());
        assertEquals(Timestamp.valueOf(explicit), rows.getTimestamp("occurred_at"),
            "已有 occurred_at 的行不得被回填覆盖");
      }
    }
  }

  /** 读脚本并拆成可执行语句：去掉 `--` 注释行，按 `;` 切分，忽略空白片段。 */
  private static List<String> statementsOf(String path) throws Exception {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String line : Files.readAllLines(Path.of(path))) {
      String trimmed = line.trim();
      if (trimmed.startsWith("--") || trimmed.isEmpty()) {
        continue;
      }
      current.append(line).append('\n');
      if (trimmed.endsWith(";")) {
        String sql = current.toString().trim();
        statements.add(sql.substring(0, sql.length() - 1));
        current.setLength(0);
      }
    }
    if (!current.toString().isBlank()) {
      statements.add(current.toString().trim());
    }
    return statements;
  }
}
