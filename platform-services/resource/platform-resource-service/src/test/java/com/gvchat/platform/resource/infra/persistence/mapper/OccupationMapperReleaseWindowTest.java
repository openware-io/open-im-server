package com.gvchat.platform.resource.infra.persistence.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * res_occupation 释放窗口收缩的 SQL 语义回归：在 H2 内存库上跑 {@link OccupationMapper} 的真实注解 SQL
 * （不是断言 SQL 字符串），锁住四件事——释放后 {@code end_at} 收缩到释放时刻、已更早的 {@code end_at}
 * 不被反向拉长、释放后的行不再与之后的请求窗口重叠、未开始的时段被释放时区间不倒退
 * （{@code end_at >= start_at}）。
 *
 * <p>背景（ACK 库实测）：开台占用写的是 {@code openedAt + 24h}（订单侧冲突判定窗口，本次不动的部分），
 * 释放只翻状态、不收缩 {@code end_at}，于是 res_occupation 16 行全是 24 小时窗口；
 * 任何按 {@code start_at → end_at} 读「实际使用时长 / 资源利用率」的读方都会把一次开台算成 24 小时。
 */
class OccupationMapperReleaseWindowTest {

  /** 开台时刻（订单侧 occupy 传入的 startAt）。 */
  private static final LocalDateTime OPENED_AT = LocalDateTime.of(2026, 3, 1, 20, 0);
  /** 订单侧写死的 24 小时冲突判定窗口（本次修复不触碰订单侧）。 */
  private static final LocalDateTime RESERVED_END_AT = OPENED_AT.plusHours(24);
  /** 结台时刻：服务层显式传给 Mapper 的释放时间戳。 */
  private static final LocalDateTime RELEASED_AT = OPENED_AT.plusHours(2);

  private DataSource dataSource;
  private SqlSessionFactory sqlSessionFactory;

  @BeforeEach
  void setUp() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:occ_release_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    ds.setUser("sa");
    ds.setPassword("");
    try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement()) {
      // 与 res_occupation 基线同构；end_at 这里放开为可空，用于覆盖 COALESCE 兜底分支
      statement.execute("CREATE TABLE res_occupation ("
          + "id BIGINT NOT NULL PRIMARY KEY, tenant_id BIGINT NOT NULL, store_id BIGINT NOT NULL, "
          + "resource_id BIGINT NOT NULL, source_type VARCHAR(32) NOT NULL, source_id BIGINT NOT NULL, "
          + "start_at TIMESTAMP(3) NOT NULL, end_at TIMESTAMP(3) NULL, status VARCHAR(24) NOT NULL, "
          + "hold_expires_at TIMESTAMP(3) NULL, version INT NOT NULL DEFAULT 0, "
          + "created_by BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP(3) NOT NULL, "
          + "updated_by BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMP(3) NOT NULL)");
    }
    this.dataSource = ds;
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setEnvironment(new Environment("release-window-test", new JdbcTransactionFactory(), ds));
    configuration.addMapper(OccupationMapper.class);
    this.sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  /** 释放后窗口收缩到释放时刻：开台写的是 24 小时，结台后只能是实际占用时长。 */
  @Test
  void releaseWithVersion_shrinksReservedWindowToReleaseMoment() throws Exception {
    insertHeld(1L, OPENED_AT, RESERVED_END_AT, 0);

    assertEquals(1, release(1L, 0, RELEASED_AT));

    Row row = row(1L);
    assertEquals("RELEASED", row.status());
    assertEquals(1, row.version(), "乐观锁仍要自增 version");
    assertEquals(RELEASED_AT, row.endAt(), "释放后 end_at 收缩到释放时刻");
    assertEquals(RELEASED_AT, row.updatedAt(), "updated_at 仍是释放时刻");
    assertEquals(Duration.ofHours(2), Duration.between(row.startAt(), row.endAt()),
        "窗口是 2 小时实际占用，不再是 24 小时");
  }

  /** 已经更早的 end_at（end_at 已过的兜底释放路径形状）保持原值，不被释放时刻反向拉长。 */
  @Test
  void releaseWithVersion_neverExtendsAlreadyEarlierEndAt() throws Exception {
    LocalDateTime earlierEndAt = OPENED_AT.plusHours(1);
    insertHeld(2L, OPENED_AT.minusHours(3), earlierEndAt, 0);

    assertEquals(1, release(2L, 0, RELEASED_AT));

    Row row = row(2L);
    assertEquals("RELEASED", row.status());
    assertEquals(earlierEndAt, row.endAt(), "已更早的 end_at 保持原值，不被拉长");
    assertTrue(row.endAt().isBefore(RELEASED_AT));
  }

  /** 释放后的行不再与之后的请求窗口重叠（同一半开区间判重规则）。 */
  @Test
  void releasedRowNoLongerOverlapsLaterRequest() throws Exception {
    insertHeld(3L, OPENED_AT, RESERVED_END_AT, 0);
    LocalDateTime laterStart = RELEASED_AT.plusHours(1);
    LocalDateTime laterEnd = RELEASED_AT.plusHours(3);

    assertEquals(1, release(3L, 0, RELEASED_AT));

    Row row = row(3L);
    assertFalse(overlaps(row.startAt(), row.endAt(), laterStart, laterEnd),
        "收缩后的窗口 [开台, 结台) 与之后的请求窗口不重叠");
    assertTrue(overlaps(row.startAt(), RESERVED_END_AT, laterStart, laterEnd),
        "未收缩的 24 小时窗口命中重叠：断言确实能捕获回归");
  }

  /** end_at 为 NULL 时兜底取释放时刻（COALESCE 分支）。 */
  @Test
  void releaseWithVersion_nullEndAtFallsBackToReleaseMoment() throws Exception {
    insertHeld(4L, OPENED_AT, null, 0);

    assertEquals(1, release(4L, 0, RELEASED_AT));

    assertEquals(RELEASED_AT, row(4L).endAt(), "end_at 为 NULL 时取释放时刻");
  }

  /** 起点 20:00、hold_expires_at 20:15（释放时仍在未来）、20:05 释放：窗口收缩到释放时刻且不早于 start_at。 */
  @Test
  void releaseWithVersion_shrinksToReleaseMomentWithinStartedWindow() throws Exception {
    insertHeld(6L, OPENED_AT, RESERVED_END_AT, OPENED_AT.plusMinutes(15), 0);

    assertEquals(1, release(6L, 0, OPENED_AT.plusMinutes(5)));

    Row row = row(6L);
    assertEquals(OPENED_AT.plusMinutes(5), row.endAt(), "释放时刻即窗口终点");
    assertFalse(row.endAt().isBefore(row.startAt()), "区间不倒退：end_at >= start_at");
  }

  /**
   * 未开始的时段被释放（B 端 caller-supplied {@code holdExpiresAt} 早于 {@code start_at} 的未来时段 HELD
   * 超时丢弃）：收缩结果本会落到 {@code start_at} 之前，{@code GREATEST(start_at, ...)} 兜到零长度区间，
   * 绝不能出现 {@code end_at < start_at} 的负长度窗口。
   */
  @Test
  void releaseWithVersion_clampsToStartAtWhenWindowNotStarted() throws Exception {
    LocalDateTime futureStart = OPENED_AT.plusHours(2);
    LocalDateTime futureEnd = futureStart.plusHours(1);
    // 20:15 预占超时、20:30 兜底释放，而业务时段 22:00-23:00 还没开始
    insertHeld(7L, futureStart, futureEnd, OPENED_AT.plusMinutes(15), 0);

    assertEquals(1, release(7L, 0, OPENED_AT.plusMinutes(30)));

    Row row = row(7L);
    assertEquals(futureStart, row.endAt(), "未开始即释放：收缩结果兜到 start_at（零长度区间）");
    assertEquals(0, Duration.between(row.startAt(), row.endAt()).toMinutes());
    assertFalse(row.endAt().isBefore(row.startAt()), "区间不倒退：end_at >= start_at");
  }

  /** version 不匹配时 CAS 不生效，窗口与状态都必须原样不动。 */
  @Test
  void releaseWithVersion_staleVersionLeavesWindowUntouched() throws Exception {
    insertHeld(5L, OPENED_AT, RESERVED_END_AT, 2);

    assertEquals(0, release(5L, 1, RELEASED_AT), "version 不匹配时受影响 0 行");

    Row row = row(5L);
    assertEquals("HELD", row.status());
    assertEquals(2, row.version());
    assertEquals(RESERVED_END_AT, row.endAt(), "CAS 未命中不得动窗口");
  }

  /** 插入一行开台占用（HELD）；endAt 为 null 用于覆盖 COALESCE 兜底分支。 */
  private void insertHeld(long id, LocalDateTime startAt, LocalDateTime endAt, int version) throws SQLException {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement ps = connection.prepareStatement(
             "INSERT INTO res_occupation (id, tenant_id, store_id, resource_id, source_type, source_id, "
                 + "start_at, end_at, status, version, created_at, updated_at) "
                 + "VALUES (?, 1, 100, 9, 'ORDER', 77, ?, ?, 'HELD', ?, ?, ?)")) {
      ps.setLong(1, id);
      ps.setObject(2, startAt);
      if (endAt == null) {
        ps.setNull(3, Types.TIMESTAMP);
      } else {
        ps.setObject(3, endAt);
      }
      ps.setInt(4, version);
      ps.setObject(5, startAt);
      ps.setObject(6, startAt);
      ps.executeUpdate();
    }
  }

  /**
   * 插入一行开台占用（HELD）并显式写入 {@code hold_expires_at}（B 端 caller-supplied 预占超时）。
   * SQL 本身不读该列，写上只为让「未来时段预占超时后释放」的场景可读。
   */
  private void insertHeld(long id, LocalDateTime startAt, LocalDateTime endAt,
                          LocalDateTime holdExpiresAt, int version) throws SQLException {
    insertHeld(id, startAt, endAt, version);
    try (Connection connection = dataSource.getConnection();
         PreparedStatement ps = connection.prepareStatement(
             "UPDATE res_occupation SET hold_expires_at = ? WHERE id = ?")) {
      ps.setObject(1, holdExpiresAt);
      ps.setLong(2, id);
      ps.executeUpdate();
    }
  }

  /** 走真实 Mapper 注解 SQL（自动提交）。 */
  private int release(long id, int version, LocalDateTime releasedAt) {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      return session.getMapper(OccupationMapper.class).releaseWithVersion(id, version, releasedAt);
    }
  }

  /** 直接 JDBC 读回落库行，避免用被测框架的映射掩盖问题。 */
  private Row row(long id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement ps = connection.prepareStatement(
             "SELECT status, version, start_at, end_at, updated_at FROM res_occupation WHERE id = ?")) {
      ps.setLong(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "占用行应存在: " + id);
        return new Row(rs.getString("status"), rs.getInt("version"),
            rs.getObject("start_at", LocalDateTime.class),
            rs.getObject("end_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));
      }
    }
  }

  /** 与 OccupationApplicationService.holdResource 相同的半开区间 [start,end) 判重规则。 */
  private static boolean overlaps(LocalDateTime aStart, LocalDateTime aEnd,
                                  LocalDateTime bStart, LocalDateTime bEnd) {
    return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
  }

  private record Row(String status, int version, LocalDateTime startAt, LocalDateTime endAt,
                     LocalDateTime updatedAt) {}
}
