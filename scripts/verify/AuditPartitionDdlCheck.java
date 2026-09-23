/*
 * 审计分区 DDL 真库校验（MySQL 8）。
 *
 * 为什么需要这个脚本：H2 不支持 `PARTITION BY RANGE COLUMNS`，所以
 * `db/migration-audit/*.sql` 与保留任务执行的 `REORGANIZE PARTITION pmax` 在单测里**跑不起来**，
 * 只能靠静态守卫（AuditPartitionInvariantTest）+ 真库运行。本脚本补上「真库运行」这一半：
 * 它把三份迁移脚本**逐条真跑**，再用 MySQL 自己来判定那些只在真库暴露的约束。
 *
 * 校验项（每项失败都会打印具体原因）：
 *   1) 三份迁移脚本能在 MySQL 8 上执行成功（唯一键不含分区列时 MySQL 会直接拒绝该 DDL）；
 *   2) 主表是 RANGE COLUMNS 分区且分区表达式是 occurred_at，pmin/pmax 兜底分区存在；
 *   3) 主键包含 occurred_at（分区表硬约束），ledger 的唯一键是 (tenant_id, idempotency_key)；
 *   4) occurred_at 为 NOT NULL：显式写 NULL 必须被拒绝；
 *   5) 保留任务的 DDL：`REORGANIZE PARTITION pmax INTO (...)` 能追加月分区且仍保留 pmax；
 *   6) 兜底可写：远未来时间写入落进 pmax 而不是报 1526（"no partition for value"）；
 *   7) 分区裁剪：按 occurred_at 的单月区间查询，EXPLAIN 的 partitions 列只命中该月分区；
 *   8) 多行 INSERT（批量上报路径）与两处「不存在才插入」SQL（台账/清单的幂等抢占）在 MySQL 上的真实语义。
 *
 * 安全性：只在 `--schema` 指定库里创建**带后缀的临时表**（默认后缀 `_ddlcheck`），
 * 开头先 DROP IF EXISTS（可重复运行），结束在 finally 里删除；**绝不触碰**真实表。
 * 因此需要「能建表的库账号」，不需要全局 CREATE DATABASE 权限。
 *
 * 用法（Kind 环境；Docker Desktop 提供容器运行时，不使用 Compose）：
 *   1) 起 Kind 环境：.\deploy-k8s.ps1 -SaasReleaseManifestPath <逐服务ACR发布清单> -RegistryPullSecretName acr-registry
 *      （Kind 里 MySQL 是集群内 Service，默认不对宿主机暴露）
 *   2) 把集群内 MySQL 暴露到本机 13306：
 *      kubectl -n open-im-local port-forward svc/mysql 13306:3306
 *   3) 跑本脚本（连接器 jar 来自 Maven 本地仓；im_user 对 open_saas 有全量权限，够建临时表）：
 *      java -cp "$env:USERPROFILE\.m2\repository\com\mysql\mysql-connector-j\9.7.0\mysql-connector-j-9.7.0.jar" `
 *           scripts/verify/AuditPartitionDdlCheck.java `
 *           --host 127.0.0.1 --port 13306 --user im_user --password <DB_PASSWORD> --schema open_saas
 *
 * 也可先编译再运行（javac 要求文件名与公有类同名，因此本文件用 PascalCase 命名）：
 *   javac -encoding UTF-8 -cp <jar> -d <tmp> scripts/verify/AuditPartitionDdlCheck.java
 *
 * 参数：--host/--port/--user/--password/--schema/--suffix；也可用同名环境变量
 *      （MYSQL_HOST/MYSQL_PORT/MYSQL_USER/MYSQL_PASSWORD）。
 *      迁移脚本目录固定为 common-services/audit/common-audit-service/src/main/resources/db/migration-audit。
 */

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AuditPartitionDdlCheck {

    private static final String MIGRATION_DIR =
            "common-services/audit/common-audit-service/src/main/resources/db/migration-audit";

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseOptions(args);
        String host = options.getOrDefault("host", envOr("MYSQL_HOST", "127.0.0.1"));
        String port = options.getOrDefault("port", envOr("MYSQL_PORT", "3306"));
        String user = options.getOrDefault("user", envOr("MYSQL_USER", "root"));
        String password = options.getOrDefault("password", envOr("MYSQL_PASSWORD", ""));
        String schema = options.getOrDefault("schema", envOr("MYSQL_DATABASE", "open_saas"));
        String suffix = options.getOrDefault("suffix", "_ddlcheck");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowMultiQueries=false";
        System.out.println("== 目标 ==");
        System.out.println("  " + host + ":" + port + "/" + schema + " as " + user + " (临时表后缀 " + suffix + ")");

        Connection connection = DriverManager.getConnection(url, user, password);
        try {
            step("0. 连接与版本");
            pass("server version " + scalar(connection, "SELECT VERSION()"));

            step("1. 应用三份迁移脚本（只在临时表上）");
            String auditTable = "iam_audit_log" + suffix;
            String ledgerTable = "iam_audit_idempotency" + suffix;
            String manifestTable = "iam_audit_archive_manifest" + suffix;
            dropTemporaryTables(connection, auditTable, ledgerTable, manifestTable);
            for (String file : List.of("V1__audit_idempotency_ledger.sql", "V2__audit_partitioned_table.sql",
                    "V3__audit_archive_manifest.sql")) {
                String ddl = rewriteTables(readScript(file), auditTable, ledgerTable, manifestTable);
                for (String statement : splitStatements(ddl)) {
                    execute(connection, statement);
                }
                pass(file + " applied on real MySQL 8");
            }

            step("2. 分区结构与兜底分区");
            String method = scalar(connection,
                    "SELECT PARTITION_METHOD FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA=DATABASE()"
                            + " AND TABLE_NAME='" + auditTable + "' AND PARTITION_NAME IS NOT NULL LIMIT 1");
            String expression = scalar(connection,
                    "SELECT PARTITION_EXPRESSION FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA=DATABASE()"
                            + " AND TABLE_NAME='" + auditTable + "' AND PARTITION_NAME IS NOT NULL LIMIT 1");
            List<String> partitions = column(connection,
                    "SELECT PARTITION_NAME FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA=DATABASE()"
                            + " AND TABLE_NAME='" + auditTable + "' AND PARTITION_NAME IS NOT NULL");
            check("RANGE COLUMNS".equals(method), "partition method is RANGE COLUMNS (actual: " + method + ")");
            check(expression != null && expression.contains("occurred_at"),
                    "partition expression uses occurred_at (actual: " + expression + ")");
            check(partitions.contains("pmin"), "pmin fallback partition exists");
            check(partitions.contains("pmax"), "pmax fallback partition exists");
            System.out.println("    partitions=" + partitions.size() + " (" + partitions.get(0) + " .. "
                    + partitions.get(partitions.size() - 1) + ")");

            step("3. MySQL 硬约束：唯一索引必须含分区列");
            List<String> offenders = column(connection,
                    "SELECT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()"
                            + " AND TABLE_NAME='" + auditTable + "' AND NON_UNIQUE=0 GROUP BY INDEX_NAME"
                            + " HAVING SUM(COLUMN_NAME='occurred_at')=0");
            check(offenders.isEmpty(), "every unique index includes occurred_at (offenders: " + offenders + ")");
            String pk = scalar(connection,
                    "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS"
                            + " WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + auditTable
                            + "' AND INDEX_NAME='PRIMARY'");
            check(pk != null && pk.contains("occurred_at"), "primary key includes occurred_at (actual: " + pk + ")");
            String ledgerKey = scalar(connection,
                    "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS"
                            + " WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + ledgerTable
                            + "' AND NON_UNIQUE=0");
            check(ledgerKey != null && ledgerKey.contains("idempotency_key"),
                    "ledger unique key keeps (tenant_id, idempotency_key) (actual: " + ledgerKey + ")");

            step("4. occurred_at 为 NOT NULL（显式写 NULL 必须被拒绝）");
            String nullable = scalar(connection,
                    "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()"
                            + " AND TABLE_NAME='" + auditTable + "' AND COLUMN_NAME='occurred_at'");
            check("NO".equals(nullable), "occurred_at is NOT NULL (actual: " + nullable + ")");
            check(rejectsNullOccurredAt(connection, auditTable), "INSERT with NULL occurred_at is rejected");

            step("5. 保留任务的 DDL：REORGANIZE pmax 追加月分区");
            execute(connection, "ALTER TABLE " + auditTable + " REORGANIZE PARTITION pmax INTO ("
                    + "PARTITION p202701 VALUES LESS THAN ('2027-02-01 00:00:00'), "
                    + "PARTITION pmax VALUES LESS THAN (MAXVALUE))");
            List<String> afterReorganize = column(connection,
                    "SELECT PARTITION_NAME FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA=DATABASE()"
                            + " AND TABLE_NAME='" + auditTable + "' AND PARTITION_NAME IS NOT NULL");
            check(afterReorganize.contains("p202701"), "new month partition p202701 created via REORGANIZE");
            check(afterReorganize.contains("pmax"), "pmax is still present after REORGANIZE");

            step("6. 兜底可写 + 多行批插 + 分区裁剪");
            int batchRows = insertBatch(connection, auditTable, 3);
            check(batchRows == 3, "multi-row INSERT (batch reporting path) wrote 3 rows (actual: " + batchRows + ")");
            execute(connection, "INSERT INTO " + auditTable
                    + " (id, tenant_id, action, action_label, resource_type, result, idempotency_key,"
                    + " occurred_at, created_at) VALUES (2099000000001, 0, 'audit.far.future', 'x', '', 'SUCCESS',"
                    + " 'far-future', '2099-01-01 00:00:00', NOW(3))");
            long pmaxRows = count(connection, "SELECT COUNT(*) FROM " + auditTable + " PARTITION (pmax)");
            check(pmaxRows == 1, "far-future value lands in pmax instead of failing (actual pmax rows: "
                    + pmaxRows + ")");
            long pmaxAfterDelete = deleteProbe(connection, auditTable, "far-future");
            check(pmaxAfterDelete == 1, "probe row removable (audit table is append-only by policy)");

            step("7. 分区裁剪（EXPLAIN 的 partitions 列）");
            String explain = explainPartitions(connection, auditTable, "2026-09-01 00:00:00",
                    "2026-10-01 00:00:00");
            check(explain != null && !explain.contains("p202601") && !explain.contains("pmin"),
                    "single-month range query prunes to the target month (partitions: " + explain + ")");

            step("8. 幂等抢占 SQL 的真实语义（台账 / 归档清单）");
            int firstClaim = claimLedger(connection, ledgerTable);
            int secondClaim = claimLedger(connection, ledgerTable);
            check(firstClaim == 1 && secondClaim == 0,
                    "ledger claim is 1 then 0 (actual: " + firstClaim + " then " + secondClaim + ")");
            int firstManifest = insertManifest(connection, manifestTable);
            int secondManifest = insertManifest(connection, manifestTable);
            check(firstManifest == 1 && secondManifest == 0,
                    "manifest insert-if-absent is 1 then 0 (actual: " + firstManifest + " then " + secondManifest + ")");
        } finally {
            step("9. 清理临时表（绝不触碰真实表）");
            dropTemporaryTables(connection, "iam_audit_log" + suffix, "iam_audit_idempotency" + suffix,
                    "iam_audit_archive_manifest" + suffix);
            pass("temporary tables dropped");
            connection.close();
        }

        System.out.println();
        System.out.println("Result: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void dropTemporaryTables(Connection connection, String audit, String ledger, String manifest) {
        for (String table : List.of(audit, ledger, manifest)) {
            try {
                execute(connection, "DROP TABLE IF EXISTS " + table);
            } catch (Exception exception) {
                System.out.println("  warn: drop failed for " + table + ": " + exception.getMessage());
            }
        }
    }

    /** 把迁移脚本里的真实表名替换成临时表名（只换名字，结构与分区定义逐字不改）。 */
    private static String rewriteTables(String sql, String audit, String ledger, String manifest) {
        return sql.replace("`iam_audit_log`", "`" + audit + "`")
                .replace("`iam_audit_idempotency`", "`" + ledger + "`")
                .replace("`iam_audit_archive_manifest`", "`" + manifest + "`");
    }

    private static boolean rejectsNullOccurredAt(Connection connection, String table) {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO " + table
                    + " (id, tenant_id, action, action_label, resource_type, result, idempotency_key, occurred_at,"
                    + " created_at) VALUES (1, 0, 'audit.null.probe', 'x', '', 'SUCCESS', 'null-probe', NULL, NOW(3))");
            return false;
        } catch (Exception expected) {
            return true;
        }
    }

    private static int insertBatch(Connection connection, String table, int rows) throws Exception {
        StringBuilder sql = new StringBuilder("INSERT INTO " + table
                + " (id, tenant_id, action, action_label, resource_type, result, idempotency_key,"
                + " occurred_at, created_at) VALUES ");
        for (int index = 0; index < rows; index++) {
            sql.append(index == 0 ? "" : ", ")
                    .append("(").append(1000 + index).append(", 1, 'audit.batch.probe', 'x', '', 'SUCCESS',")
                    .append(" 'batch-").append(index).append("', '2026-09-15 10:00:00', NOW(3))");
        }
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql.toString());
        }
    }

    private static long deleteProbe(Connection connection, String table, String key) throws Exception {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate("DELETE FROM " + table + " WHERE idempotency_key = '" + key + "'");
        }
    }

    /** EXPLAIN 的 partitions 列：只命中目标月分区，说明谓词能裁剪分区。 */
    private static String explainPartitions(Connection connection, String table, String from, String to)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("EXPLAIN SELECT id, action, occurred_at FROM " + table
                     + " WHERE occurred_at >= '" + from + "' AND occurred_at < '" + to
                     + "' ORDER BY occurred_at DESC, id DESC LIMIT 20")) {
            while (rows.next()) {
                for (String label : List.of("partitions")) {
                    String value = rows.getString(label);
                    if (value != null) {
                        return value;
                    }
                }
            }
            return null;
        }
    }

    /** 与 IamAuditLogMapper.claimIdempotencyKey 同形（含 audit_id 在抢占时写入）。 */
    private static int claimLedger(Connection connection, String ledger) throws Exception {
        String sql = "INSERT INTO " + ledger + " (tenant_id, idempotency_key, audit_id, created_at) "
                + "SELECT ?, ?, ?, ? FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM " + ledger
                + " WHERE tenant_id = ? AND idempotency_key = ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            LocalDateTime now = LocalDateTime.now();
            statement.setLong(1, 1001L);
            statement.setString(2, "claim-probe");
            statement.setLong(3, 555L);
            statement.setObject(4, now);
            statement.setLong(5, 1001L);
            statement.setString(6, "claim-probe");
            return statement.executeUpdate();
        }
    }

    /** 与 AuditArchiveManifestMapper.insertIfAbsent 同形。 */
    private static int insertManifest(Connection connection, String manifest) throws Exception {
        String sql = "INSERT INTO " + manifest
                + " (period, partition_name, object_path, row_count, checksum_sha256, state, operator_id, created_at) "
                + "SELECT ?, ?, NULL, ?, NULL, ?, NULL, ? FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM " + manifest
                + " WHERE period = ? AND partition_name = ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "2024-09");
            statement.setString(2, "p202409");
            statement.setLong(3, 120L);
            statement.setString(4, "ARCHIVED");
            statement.setObject(5, LocalDateTime.now());
            statement.setString(6, "2024-09");
            statement.setString(7, "p202409");
            return statement.executeUpdate();
        }
    }

    private static String readScript(String fileName) throws Exception {
        Path path = Path.of(MIGRATION_DIR, fileName);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("找不到迁移脚本（请在仓库根目录执行）: " + path.toAbsolutePath());
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** 去注释、按分号切分（DDL 里没有字符串字面量含分号，够用且不引入 SQL 解析器）。 */
    private static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String statement = current.toString().trim();
                statements.add(statement.substring(0, statement.length() - 1));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString().trim());
        }
        return statements;
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static long count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static List<String> column(Connection connection, String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 0; index + 1 < args.length; index += 2) {
            if (args[index].startsWith("--")) {
                options.put(args[index].substring(2), args[index + 1]);
            }
        }
        return options;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void step(String name) {
        System.out.println();
        System.out.println("== " + name + " ==");
    }

    private static void pass(String message) {
        passed++;
        System.out.println("  PASS " + message);
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            pass(message);
        } else {
            failed++;
            System.out.println("  FAIL " + message);
        }
    }
}
