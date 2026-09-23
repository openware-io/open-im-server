package io.openware.common.audit.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import io.openware.common.audit.domain.model.AuditLog;
import io.openware.common.audit.domain.model.OperatorDisplay;
import io.openware.common.audit.domain.repository.AuditLogRepository;
import io.openware.common.audit.infra.persistence.mapper.IamAuditLogMapper;
import io.openware.common.audit.infra.persistence.mapper.OperatorNameMapper;
import io.openware.common.audit.infra.persistence.mapper.TenantNameMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 仓储层真库回归（H2 MySQL 兼容模式）：时间范围过滤按 {@code occurred_at} 命中，且历史 NULL 行**不丢**。
 *
 * <p>用真 SQL 而不是断言 SQL 片段，覆盖现场缺陷的两种漏行姿势：
 * <ul>
 *   <li>只按 {@code occurred_at} 过滤 → 历史 NULL 行（V3 迁移前写入的）在时间筛选里整段消失；</li>
 *   <li>只按 {@code created_at} 过滤 → 补录/延迟上报（业务发生早于落库）的记录会按落库时间被错误命中。</li>
 * </ul>
 */
class AuditLogRepositoryH2QueryTest {

  private static final long TENANT_ID = 1001L;

  private SqlSessionFactory sessionFactory;
  private String jdbcUrl;

  @BeforeEach
  void setUp() throws Exception {
    jdbcUrl = "jdbc:h2:mem:audit-query-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
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
      // 操作人姓名补全读的是 open_saas.saa_admin_account（跨 schema 只读），这里建同名结构，
      // 让「按姓名/登录名检索 + 姓名补全」两段真 SQL 都能在 H2 上跑。
      statement.execute("CREATE SCHEMA IF NOT EXISTS `open_saas`");
      statement.execute("CREATE TABLE `open_saas`.`saa_admin_account` ("
          + "`id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,"
          + "`platform_account_id` bigint NULL,"
          + "`username` varchar(64) NOT NULL,"
          + "`display_name` varchar(128) NOT NULL)");
      statement.execute("INSERT INTO `open_saas`.`saa_admin_account`"
          + " (`platform_account_id`, `username`, `display_name`) VALUES (77, 'lisi', '李四')");
    }
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.addMapper(IamAuditLogMapper.class);
    configuration.addMapper(OperatorNameMapper.class);
    configuration.setEnvironment(new Environment("audit-h2", new JdbcTransactionFactory(),
        new UnpooledDataSource("org.h2.Driver", jdbcUrl, "sa", "")));
    sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    seedRows();
  }

  @AfterEach
  void tearDown() throws Exception {
    try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
         Statement statement = connection.createStatement()) {
      statement.execute("DROP ALL OBJECTS");
    }
  }

  @Test
  void timeRangeHitsRowsWithNullOccurredAtByCreatedAtWithoutDroppingThem() {
    try (SqlSession session = sessionFactory.openSession(true)) {
      AuditLogRepository repository = repository(session);

      // 2026-09-06 这一天：只有「历史 NULL 行」（created_at 落在当天）应当命中。
      List<AuditLog> rows = repository.findPage(query(repository,
          LocalDateTime.of(2026, 9, 6, 0, 0), LocalDateTime.of(2026, 9, 7, 0, 0)));
      assertEquals(1, rows.size(), "历史 occurred_at 为空的行必须按 created_at 回退命中，不能静默丢行");
      assertEquals("historical.null", rows.get(0).getAction());

      long total = repository.count(query(repository,
          LocalDateTime.of(2026, 9, 6, 0, 0), LocalDateTime.of(2026, 9, 7, 0, 0)));
      assertEquals(1, total, "count 与 findPage 必须同一套过滤口径（分页总数不能与列表不一致）");
    }
  }

  @Test
  void timeRangeUsesOccurredAtRatherThanCreatedAtForBackfilledRows() {
    try (SqlSession session = sessionFactory.openSession(true)) {
      AuditLogRepository repository = repository(session);

      // 补录行：业务发生在 09-02，落库在 09-07。按发生时间区间查它，必须命中；
      // 按落库日期区间（09-07）查，它不应出现（否则「按发生时间筛选」名不副实）。
      List<AuditLog> byOccurred = repository.findPage(query(repository,
          LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 3, 0, 0)));
      assertEquals(1, byOccurred.size());
      assertEquals("backfilled.early", byOccurred.get(0).getAction());
      assertTrue(byOccurred.get(0).getOccurredAt().isBefore(byOccurred.get(0).getCreatedAt()));

      List<AuditLog> byCreated = repository.findPage(query(repository,
          LocalDateTime.of(2026, 9, 7, 0, 0), LocalDateTime.of(2026, 9, 8, 0, 0)));
      assertEquals(0, byCreated.size(), "补录行不得按落库时间被错误命中");
    }
  }

  @Test
  void orderingUsesEffectiveOccurredTime() {
    try (SqlSession session = sessionFactory.openSession(true)) {
      AuditLogRepository repository = repository(session);

      List<AuditLog> ascending = repository.findPage(query(repository, null, null, true));

      assertEquals(List.of("backfilled.early", "normal.sept5", "historical.null"),
          ascending.stream().map(AuditLog::getAction).toList(),
          "排序按有效发生时间：补录(09-02) → 正常(09-05) → NULL 回退 created_at(09-06)");
    }
  }

  private AuditLogRepository repository(SqlSession session) {
    return new AuditLogRepositoryImpl(session.getMapper(IamAuditLogMapper.class),
        mock(TenantNameMapper.class), session.getMapper(OperatorNameMapper.class));
  }

  private static AuditLogRepository.Query query(AuditLogRepository repository, LocalDateTime from,
                                                LocalDateTime to) {
    return query(repository, from, to, false);
  }

  private static AuditLogRepository.Query query(AuditLogRepository repository, LocalDateTime from,
                                                LocalDateTime to, boolean ascending) {
    return new AuditLogRepository.Query(TENANT_ID, null, null, null, null, null, null, null, null, null, null,
        null, null, from, to, ascending, 0L, 50, 100_000L);
  }

  /**
   * 带上界的 count：匹配行数达到上界即停（只回「不少于上界」），且与 findPage 过滤口径同源。
   * 用真 SQL 在 H2（MySQL 兼容模式）跑，覆盖派生表 + LIMIT 的可移植写法。
   */
  @Test
  void cappedCountStopsAtCapAndKeepsSameFilterAsPage() {
    try (SqlSession session = sessionFactory.openSession(true)) {
      AuditLogRepository repository = repository(session);

      AuditLogRepository.Query capped = new AuditLogRepository.Query(TENANT_ID, null, null, null, null, null,
          null, null, null, null, null, null, null, null, null, false, 0L, 50, 2L);

      assertEquals(2, repository.count(capped), "3 条匹配行在上界 2 下只能证明『不少于 2』");
      assertEquals(3, repository.count(query(repository, null, null)),
          "上界足够大时就是精确总数，口径与 findPage 一致");
      assertEquals(3, repository.findPage(query(repository, null, null)).size());
    }
  }

  private void seedRows() throws Exception {
    try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
         Statement statement = connection.createStatement()) {
      // 正常行：发生时间与落库时间一致。
      statement.execute(insert("normal.sept5", "2026-09-05 10:00:00", "2026-09-05 10:00:00"));
      // 历史 NULL 行：V3 迁移前写入、occurred_at 为空的存量数据（查询必须回退 created_at）。
      statement.execute(insert("historical.null", null, "2026-09-06 10:00:00"));
      // 补录行：业务发生在 09-02，落库在 09-07。
      statement.execute(insert("backfilled.early", "2026-09-02 10:00:00", "2026-09-07 10:00:00"));
    }
  }

  /**
   * 操作人姓名补全 + 按姓名/登录名检索：审计表只冗余 {@code operator_id}，
   * 姓名与登录名按它到 {@code open_saas.saa_admin_account} 取（真 SQL，跨 schema 限定写法）。
   *
   * <p>历史数据里 {@code operator_name}/{@code operator_account} 常为空（上报方没带），
   * 这时列表既要把姓名补出来，也要能按姓名搜到——只按审计表字段匹配会一条都查不到。
   */
  @Test
  void operatorDisplayResolvesFromAccountTableByRedundantOperatorId() throws Exception {
    try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
         Statement statement = connection.createStatement()) {
      statement.execute("INSERT INTO `iam_audit_log` (`tenant_id`, `operator_id`, `operator_type`, `action`,"
          + " `action_label`, `resource_type`, `result`, `idempotency_key`, `occurred_at`, `created_at`)"
          + " VALUES (" + TENANT_ID + ", 77, 'TENANT', 'iam.role.create', 'IAM 角色创建', 'iam_role',"
          + " 'SUCCESS', 'iam.role.create:1', '2026-09-08 10:00:00', '2026-09-08 10:00:00')");
    }
    try (SqlSession session = sessionFactory.openSession(true)) {
      AuditLogRepository repository = repository(session);

      Map<Long, OperatorDisplay> displays = repository.operatorDisplays(List.of(77L));
      assertEquals("李四", displays.get(77L).name(), "姓名取账号表 display_name");
      assertEquals("lisi", displays.get(77L).account(), "登录名取账号表 username");
      assertTrue(repository.operatorDisplays(List.of(0L)).isEmpty(),
          "operator_id=0 表示上报方没带操作人（如保留任务自留审计），不必查账号表");

      assertEquals(1, repository.findPage(keywordQuery("李四")).size(), "按姓名要能搜到未存姓名的历史记录");
      assertEquals(1, repository.findPage(keywordQuery("lisi")).size(), "按登录名同样要能搜到");
      assertEquals(1, repository.count(keywordQuery("李四")), "count 与 findPage 必须同一套关键字口径");
      assertEquals(0, repository.findPage(keywordQuery("查无此人")).size());
    }
  }

  private static AuditLogRepository.Query keywordQuery(String keyword) {
    return new AuditLogRepository.Query(TENANT_ID, null, null, null, keyword, null, null, null, null, null, null,
        null, null, null, null, false, 0L, 50, 100_000L);
  }

  private static String insert(String action, String occurredAt, String createdAt) {
    return "INSERT INTO `iam_audit_log` (`tenant_id`, `action`, `action_label`, `resource_type`,"
        + " `result`, `idempotency_key`, `occurred_at`, `created_at`) VALUES (" + TENANT_ID
        + ", '" + action + "', '" + action + "', 'ord_order', 'SUCCESS', '" + action + ":1', "
        + (occurredAt == null ? "NULL" : "'" + occurredAt + "'") + ", '" + createdAt + "')";
  }
}
