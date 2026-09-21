package com.gvchat.common.audit.infra.persistence.migration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 分区表不变量守卫（静态扫描迁移脚本）。
 *
 * <p>为什么必须用静态用例守：**H2 不支持分区 DDL**（{@code MODE=MySQL} 也不支持
 * {@code PARTITION BY RANGE COLUMNS}），所以分区表的约束在单测里跑不起来，
 * 只能在 MySQL 上才暴露。而这里最容易踩、代价最大的一条是：
 *
 * <blockquote>
 * MySQL 要求分区表的**每个唯一索引（含主键）都必须包含分区表达式用到的所有列**。
 * </blockquote>
 *
 * <p>原始设计正是被这一条卡住的：审计主表原本有 {@code UNIQUE (tenant_id, idempotency_key)}，
 * 一旦按时间分区，这个唯一键必须补上时间列——而 {@code created_at} 是插入时刻的毫秒值，
 * 重试晚 1ms 就不再冲突，**幂等会静默失效**（BFF 拦截器与领域服务双写同一次操作变两条痕迹）。
 * 该用例把「分区列必须在每个唯一索引里」和「兜底分区必须存在」钉死，
 * 避免以后有人顺手把唯一键加回分区主表。
 */
class AuditPartitionInvariantTest {

  /** 新库基线目录（profile=audit-schema 时启用）：分区主表在 V2。 */
  private static final String PARTITIONED_MIGRATION =
      "src/main/resources/db/migration-audit/V2__audit_partitioned_table.sql";

  /** 幂等台账在同一个新库基线目录的 V1。 */
  private static final String LEDGER_MIGRATION =
      "src/main/resources/db/migration-audit/V1__audit_idempotency_ledger.sql";

  /** 分区键列名（与方案 §2.1 一致：按业务发生时间分区，前端时间筛选与排序都用它）。 */
  private static final String PARTITION_COLUMN = "occurred_at";

  @Test
  void partitionedTableIsRangePartitionedByOccurredAt() throws Exception {
    String sql = read(PARTITIONED_MIGRATION).toLowerCase(Locale.ROOT);

    assertTrue(sql.contains("partition by range columns(`" + PARTITION_COLUMN + "`)"),
        "审计主表必须按 `" + PARTITION_COLUMN + "` 做 RANGE COLUMNS 分区"
            + "（按 created_at 分区会导致时间筛选无法裁剪分区）: " + PARTITIONED_MIGRATION);
    assertTrue(sql.contains("partition `pmin`"), "缺少 pmin 兜底分区：异常早的时间也必须写得进去");
    assertTrue(sql.contains("partition `pmax`"), "缺少 pmax 兜底分区：新月份未预建时必须写得进去");
    assertTrue(sql.contains("values less than (maxvalue)"), "pmax 必须是 MAXVALUE 上界");
  }

  /** 核心不变量：分区主表的每个唯一索引（含主键）都必须包含分区列，否则 MySQL 直接拒绝该 DDL。 */
  @Test
  void everyUniqueKeyOfPartitionedTableIncludesPartitionColumn() throws Exception {
    String definition = tableDefinition(read(PARTITIONED_MIGRATION), "iam_audit_log");
    List<String> uniqueKeys = uniqueKeysOf(definition);

    assertFalse(uniqueKeys.isEmpty(),
        "未解析到任何主键/唯一键——守卫用例必须至少校验主键，否则会虚假变绿: " + uniqueKeys);
    for (String key : uniqueKeys) {
      assertTrue(key.contains(PARTITION_COLUMN),
          "分区表的唯一索引必须包含分区列 `" + PARTITION_COLUMN + "`（MySQL 硬约束），实际: " + key);
    }
  }

  /**
   * 幂等唯一性只能留在台账表上。
   *
   * <p>反向断言：分区主表**不得**出现 {@code (tenant_id, idempotency_key)} 这类不含分区列的唯一键
   * ——它就是「重试晚 1ms 即失效」的那个写法，必须靠台账（V4）承担。
   */
  @Test
  void idempotencyUniquenessStaysInLedgerInsteadOfPartitionedTable() throws Exception {
    String partitionedTable = tableDefinition(read(PARTITIONED_MIGRATION), "iam_audit_log").toLowerCase(Locale.ROOT);
    String ledger = read(LEDGER_MIGRATION).toLowerCase(Locale.ROOT);

    assertFalse(partitionedTable.matches("(?s).*unique\\s+key[^,)]*idempotency_key.*"),
        "分区主表不得保留幂等唯一键（会被迫补时间列 → 重试晚 1ms 不再冲突 → 幂等静默失效）");
    assertTrue(ledger.contains("primary key (`tenant_id`, `idempotency_key`)"),
        "幂等唯一键必须落在台账表 iam_audit_idempotency 上");
  }

  /**
   * 两个迁移目录必须物理隔离：新库基线（migration-audit）与老库历史（migration）版本号互不复用。
   *
   * <p>混进同一个目录会同时坏掉两条路：老库部署时新库迁移会对着已存在的普通表再 `CREATE TABLE`（1050），
   * 新库首次迁移则会被老库 V1 先建成普通表、再被分区主表 DDL 撞上。
   */
  @Test
  void newSchemaBaselineIsNotMixedIntoLegacyMigrationDirectory() throws Exception {
    Path legacyDir = Path.of("src/main/resources/db/migration");
    try (var files = Files.list(legacyDir)) {
      List<String> legacy = files.map(path -> path.getFileName().toString()).sorted().toList();
      assertTrue(legacy.stream().noneMatch(name -> name.contains("partitioned")),
          "分区主表迁移不得留在老库目录（否则老库部署会 1050 失败）: " + legacy);
    }
    assertTrue(Files.isRegularFile(Path.of(PARTITIONED_MIGRATION)),
        "分区主表迁移必须在新库基线目录: " + PARTITIONED_MIGRATION);
    assertTrue(Files.isRegularFile(Path.of(LEDGER_MIGRATION)),
        "幂等台账迁移必须在新库基线目录: " + LEDGER_MIGRATION);
  }

  private static String read(String relativePath) throws Exception {
    return new String(Files.readAllBytes(Path.of(relativePath)), StandardCharsets.UTF_8);
  }

  /** 截取 {@code CREATE TABLE `name` (...)} 的括号内定义（用括号配平，避免正则跨表误伤）。 */
  private static String tableDefinition(String sql, String table) {
    String lower = sql.toLowerCase(Locale.ROOT);
    int start = lower.indexOf("create table `" + table + "`");
    assertTrue(start >= 0, "未在迁移脚本里找到建表语句: " + table);
    int open = lower.indexOf('(', start);
    int depth = 0;
    for (int index = open; index < sql.length(); index++) {
      char current = sql.charAt(index);
      if (current == '(') {
        depth++;
      } else if (current == ')') {
        depth--;
        if (depth == 0) {
          return sql.substring(open + 1, index);
        }
      }
    }
    throw new IllegalStateException("建表语句括号不配平: " + table);
  }

  /** 取出 PRIMARY KEY 与 UNIQUE KEY 的定义片段（列清单）。 */
  private static List<String> uniqueKeysOf(String definition) {
    List<String> keys = new ArrayList<>();
    Matcher matcher = Pattern.compile("(?i)(primary\\s+key|unique\\s+key\\s+`[^`]+`)\\s*\\(([^)]*)\\)")
        .matcher(definition);
    while (matcher.find()) {
      keys.add(matcher.group(1).toLowerCase(Locale.ROOT) + " (" + matcher.group(2).toLowerCase(Locale.ROOT) + ")");
    }
    return keys;
  }
}
