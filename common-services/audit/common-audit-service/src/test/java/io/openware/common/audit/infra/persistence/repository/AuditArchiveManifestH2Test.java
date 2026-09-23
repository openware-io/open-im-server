package io.openware.common.audit.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import io.openware.common.audit.domain.repository.AuditArchiveManifestRepository;
import io.openware.common.audit.domain.retention.AuditArchiveManifest;
import io.openware.common.audit.infra.persistence.mapper.AuditArchiveManifestMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 归档清单真库回归（H2 MySQL 兼容模式）：清单是「哪些月份已归档」的唯一证据，必须幂等且状态语义准确。
 *
 * <p>保留任务按小时运行，会反复处理同一批月份，因此这里钉住三条：
 * <ol>
 *   <li>同一 {@code (period, partition_name)} 重复登记只留一条，且**不覆盖**首次的行数快照
 *       （行数将来要参与「删除前复核」，被后来的 0 覆盖会让复核失去意义）；</li>
 *   <li>{@code archivedMonths()} 只取 {@code ARCHIVED}：{@code HELD}（法律保留）月份不进集合，
 *       因此不会被算进可查下限——保留月必须仍然可查；</li>
 *   <li>清单按月份倒序返回，便于巡检先看最近的归档。</li>
 * </ol>
 */
class AuditArchiveManifestH2Test {

    private SqlSessionFactory sessionFactory;
    private String jdbcUrl;

    @BeforeEach
    void setUp() throws Exception {
        jdbcUrl = "jdbc:h2:mem:audit-manifest-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            // 与 db/migration-audit/V3__audit_archive_manifest.sql 同形。
            statement.execute("CREATE TABLE `iam_audit_archive_manifest` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "`period` varchar(7) NOT NULL,"
                + "`partition_name` varchar(64) NOT NULL,"
                + "`object_path` varchar(512) NULL,"
                + "`row_count` bigint NOT NULL DEFAULT 0,"
                + "`checksum_sha256` varchar(64) NULL,"
                + "`state` varchar(16) NOT NULL,"
                + "`operator_id` bigint NULL,"
                + "`created_at` timestamp(3) NOT NULL,"
                + "`dropped_at` timestamp(3) NULL,"
                + "UNIQUE (`period`, `partition_name`))");
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AuditArchiveManifestMapper.class);
        configuration.setEnvironment(new Environment("audit-manifest-h2", new JdbcTransactionFactory(),
            new UnpooledDataSource("org.h2.Driver", jdbcUrl, "sa", "")));
        sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void markArchivedIsIdempotentAndKeepsFirstRowCountSnapshot() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            AuditArchiveManifestRepository repository = repository(session);

            repository.markArchived(YearMonth.of(2024, 9), "p202409", 120L, null);
            repository.markArchived(YearMonth.of(2024, 9), "p202409", 0L, null);

            assertEquals(1, countOf("iam_audit_archive_manifest"), "同一月份重复登记只留一条");
            assertEquals(120L, rowCountOf("2024-09"), "行数快照属于首次登记，不能被后来的 0 覆盖");
        }
    }

    @Test
    void holdStateIsRecordedSeparatelyAndExcludedFromArchivedMonths() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            AuditArchiveManifestRepository repository = repository(session);

            repository.markArchived(YearMonth.of(2024, 8), "p202408", 10L, null);
            repository.markHeld(YearMonth.of(2024, 9), "p202409", null);

            Set<YearMonth> archived = repository.archivedMonths();
            assertEquals(Set.of(YearMonth.of(2024, 8)), archived,
                "HELD（法律保留）月份不得进入 archivedMonths——它必须仍然可查");
            assertEquals(2, countOf("iam_audit_archive_manifest"), "保留月份也要留痕，便于巡检看到保留意图");
        }
    }

    @Test
    void manifestIsReturnedNewestFirst() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            AuditArchiveManifestRepository repository = repository(session);
            repository.markArchived(YearMonth.of(2024, 7), "p202407", 1L, null);
            repository.markArchived(YearMonth.of(2024, 9), "p202409", 3L, null);
            repository.markArchived(YearMonth.of(2024, 8), "p202408", 2L, null);

            List<AuditArchiveManifestRepository.AuditArchiveManifestView> all = repository.findAll();

            assertEquals(List.of("2024-09", "2024-08", "2024-07"),
                all.stream().map(view -> view.period().toString()).toList());
            assertTrue(all.stream().allMatch(view -> AuditArchiveManifest.STATE_ARCHIVED.equals(view.state())));
        }
    }

    private AuditArchiveManifestRepository repository(SqlSession session) {
        return new AuditArchiveManifestRepositoryImpl(session.getMapper(AuditArchiveManifestMapper.class));
    }

    private long countOf(String table) {
        return scalar("SELECT COUNT(*) FROM `" + table + "`");
    }

    private long rowCountOf(String period) {
        return scalar("SELECT row_count FROM `iam_audit_archive_manifest` WHERE period = '" + period + "'");
    }

    private long scalar(String sql) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        } catch (Exception exception) {
            throw new IllegalStateException("查询失败: " + sql, exception);
        }
    }
}
