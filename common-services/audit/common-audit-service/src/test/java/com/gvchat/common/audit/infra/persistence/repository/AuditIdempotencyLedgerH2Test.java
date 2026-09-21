package com.gvchat.common.audit.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.gvchat.common.audit.domain.model.AuditLog;
import com.gvchat.common.audit.domain.repository.AuditLogRepository;
import com.gvchat.common.audit.infra.persistence.mapper.IamAuditLogMapper;
import com.gvchat.common.audit.infra.persistence.mapper.OperatorNameMapper;
import com.gvchat.common.audit.infra.persistence.mapper.TenantNameMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 幂等台账真库回归（H2 MySQL 兼容模式）：唯一性职责已从审计主表迁到 {@code iam_audit_idempotency}，
 * 因为主表要按 {@code occurred_at} 月分区，而 MySQL 不允许分区表保留不含分区列的唯一键
 * （方案 docs/renovation/AUDIT_STORAGE_01_SERVICE.md §2.2/§3）。
 *
 * <p>这里钉住三条不可回退的语义：
 * <ol>
 *   <li>同租户同幂等键重复上报 → 只落一条主表记录，第二次回执原记录 ID 且 {@code duplicated=true}
 *       （BFF 拦截器与领域服务双写同一次操作不能变成两条痕迹）；</li>
 *   <li>不同幂等键（含同键不同租户）→ 各自落库；</li>
 *   <li>台账有键但未回填记录 ID（异常中断的恢复分支）→ 补写记录而不是当重复丢掉
 *       ——审计宁可重复也不能丢失。</li>
 * </ol>
 */
class AuditIdempotencyLedgerH2Test {

  private static final long TENANT_ID = 1001L;

  /** 主键由应用侧生成，测试里用递增序列模拟（真实实现是雪花 ID）。 */
  private static final AtomicLong NEXT_ID = new AtomicLong(900_000L);

  private SqlSessionFactory sessionFactory;
  private String jdbcUrl;

  @BeforeEach
  void setUp() throws Exception {
    jdbcUrl = "jdbc:h2:mem:audit-idem-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
    try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE `iam_audit_log` ("
          + "`id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,"
          + "`tenant_id` bigint NOT NULL DEFAULT 0,"
          + "`organization_id` bigint NULL,"
          + "`store_id` bigint NULL,"
          + "`operator_id` bigint NULL,"
          + "`operator_name` varchar(128) NULL,"
          + "`operator_account` varchar(128) NULL,"
          + "`operator_type` varchar(16) NOT NULL DEFAULT 'TENANT',"
          + "`action` varchar(128) NOT NULL,"
          + "`action_label` varchar(128) NOT NULL DEFAULT '',"
          + "`resource_type` varchar(64) NOT NULL DEFAULT '',"
          + "`resource_id` varchar(128) NULL,"
          + "`resource_name` varchar(255) NULL,"
          + "`result` varchar(16) NOT NULL DEFAULT 'SUCCESS',"
          + "`error_code` varchar(64) NULL,"
          + "`ip` varchar(45) NULL,"
          + "`user_agent` varchar(512) NULL,"
          + "`request_id` varchar(64) NULL,"
          + "`trace_id` varchar(64) NULL,"
          + "`source_service` varchar(64) NULL,"
          + "`idempotency_key` varchar(128) NOT NULL DEFAULT '',"
          + "`detail_json` varchar(2000) NULL,"
          + "`occurred_at` timestamp(3) NULL,"
          + "`created_at` timestamp(3) NOT NULL)");
      // 与 V4__audit_idempotency_ledger.sql 同形（主键即幂等唯一键）。
      statement.execute("CREATE TABLE `iam_audit_idempotency` ("
          + "`tenant_id` bigint NOT NULL,"
          + "`idempotency_key` varchar(128) NOT NULL,"
          + "`audit_id` bigint NULL,"
          + "`created_at` timestamp(3) NOT NULL,"
          + "PRIMARY KEY (`tenant_id`, `idempotency_key`))");
    }
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.addMapper(IamAuditLogMapper.class);
    configuration.setEnvironment(new Environment("audit-idem-h2", new JdbcTransactionFactory(),
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
  void sameKeyIsWrittenOnceAndSecondReportReturnsOriginalId() {
    try (SqlSession session = sessionFactory.openSession(true)) {
      AuditLogRepository repository = repository(session);

      AuditLogRepository.SaveResult first = repository.saveIfAbsent(record(TENANT_ID, "req-1", "order.settle"));
      AuditLogRepository.SaveResult second = repository.saveIfAbsent(record(TENANT_ID, "req-1", "order.settle"));

      assertFalse(first.duplicated(), "首次上报应真正落库");
      assertTrue(second.duplicated(), "重复上报必须命中幂等台账");
      assertEquals(first.id(), second.id(), "重复上报要回执首次落库的记录 ID");
      assertEquals(1, countOf("iam_audit_log"), "重复上报不得在主表留下第二条痕迹");
    }
  }

  @Test
  void differentKeysAndDifferentTenantsAreStoredSeparately() {
    try (SqlSession session = sessionFactory.openSession(true)) {
      AuditLogRepository repository = repository(session);

      repository.saveIfAbsent(record(TENANT_ID, "req-1", "order.settle"));
      repository.saveIfAbsent(record(TENANT_ID, "req-2", "order.settle"));
      repository.saveIfAbsent(record(2002L, "req-1", "order.settle"));

      assertEquals(3, countOf("iam_audit_log"), "幂等键按 (tenant_id, idempotency_key) 收敛，不跨租户误合并");
    }
  }

  @Test
  void claimedButUnattachedKeyStillWritesTheRecord() throws Exception {
    try (SqlSession session = sessionFactory.openSession(true)) {
      AuditLogRepository repository = repository(session);
      // 造出「台账有键、audit_id 为空」的异常中断残留。
      try (Statement statement = session.getConnection().createStatement()) {
        statement.execute("INSERT INTO `iam_audit_idempotency` VALUES (" + TENANT_ID + ", 'req-9', NULL, NOW())");
      }

      AuditLogRepository.SaveResult result = repository.saveIfAbsent(record(TENANT_ID, "req-9", "order.settle"));

      assertFalse(result.duplicated(), "台账有键但无记录时按未写入补写，绝不静默丢审计");
      assertEquals(1, countOf("iam_audit_log"));
      assertEquals(result.id(), claimedAuditId("req-9"), "补写后必须回填台账记录 ID");
    }
  }

  private AuditLogRepository repository(SqlSession session) {
    return new AuditLogRepositoryImpl(session.getMapper(IamAuditLogMapper.class),
        mock(TenantNameMapper.class), mock(OperatorNameMapper.class));
  }

  private static AuditLog record(long tenantId, String idempotencyKey, String action) {
    return record(tenantId, idempotencyKey, action, NEXT_ID.getAndIncrement());
  }

  /** 主键由应用侧生成（多行批插要插入前就知道 ID），测试里用递增序列模拟。 */
  private static AuditLog record(long tenantId, String idempotencyKey, String action, long id) {
    AuditLog log = new AuditLog();
    LocalDateTime now = LocalDateTime.of(2026, 9, 19, 12, 0);
    log.setId(id);
    log.setTenantId(tenantId);
    log.setAction(action);
    log.setActionLabel("结台结算");
    log.setResourceType("ord_order");
    log.setResult(AuditLog.RESULT_SUCCESS);
    log.setIdempotencyKey(idempotencyKey);
    log.setOperatorId(77L);
    log.setCreatedAt(now);
    log.setOccurredAt(now);
    return log;
  }

  /** 批量写入：台账逐条抢占、主表一次多行插入，返回顺序与入参一致。 */
  @Test
  void batchWritesFreshRowsAndReportsDuplicatesWithOriginalId() {
    try (SqlSession session = sessionFactory.openSession(true)) {
      AuditLogRepository repository = repository(session);
      AuditLogRepository.SaveResult first =
          repository.saveIfAbsent(record(TENANT_ID, "req-A", "order.settle"));

      List<AuditLogRepository.SaveResult> results = repository.saveAllIfAbsent(List.of(
          record(TENANT_ID, "req-A", "order.settle"),
          record(TENANT_ID, "req-B", "order.settle"),
          record(TENANT_ID, "req-C", "payment.collect")));

      assertEquals(3, results.size(), "批量结果必须与入参一一对应");
      assertTrue(results.get(0).duplicated(), "批内重复键应判定为重复");
      assertEquals(first.id(), results.get(0).id(), "重复项要回执首次落库的 ID");
      assertFalse(results.get(1).duplicated());
      assertFalse(results.get(2).duplicated());
      assertEquals(3, countOf("iam_audit_log"), "2 条新增 + 1 条既有 = 3，重复项不得再插一行");
    }
  }

  /** 批量写入同样受幂等台账保护：重复批次不得在主表留下第二份痕迹。 */
  @Test
  void repeatedBatchIsIdempotent() {
    try (SqlSession session = sessionFactory.openSession(true)) {
      AuditLogRepository repository = repository(session);
      List<AuditLog> batch = List.of(record(TENANT_ID, "batch-1", "order.settle"),
          record(TENANT_ID, "batch-2", "order.settle"));

      repository.saveAllIfAbsent(batch);
      List<AuditLogRepository.SaveResult> again = repository.saveAllIfAbsent(batch);

      assertTrue(again.stream().allMatch(AuditLogRepository.SaveResult::duplicated));
      assertEquals(2, countOf("iam_audit_log"), "重复批次不得重复落库");
    }
  }

  private long countOf(String table) {
    try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
         Statement statement = connection.createStatement();
         ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
      rows.next();
      return rows.getLong(1);
    } catch (Exception exception) {
      throw new IllegalStateException("统计失败: " + table, exception);
    }
  }

  private Long claimedAuditId(String idempotencyKey) {
    try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
         Statement statement = connection.createStatement();
         ResultSet rows = statement.executeQuery(
             "SELECT audit_id FROM `iam_audit_idempotency` WHERE tenant_id = " + TENANT_ID
                 + " AND idempotency_key = '" + idempotencyKey + "'")) {
      if (!rows.next()) {
        return null;
      }
      long value = rows.getLong(1);
      return rows.wasNull() ? null : value;
    } catch (Exception exception) {
      throw new IllegalStateException("读取台账失败", exception);
    }
  }
}
