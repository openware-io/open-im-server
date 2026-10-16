package io.openware.platform.order.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.order.api.dto.KtvSessionView;
import io.openware.platform.order.domain.ktv.model.KtvServerSession;
import io.openware.platform.order.domain.ktv.model.KtvSession;
import io.openware.platform.order.infra.persistence.mapper.KtvServerSessionMapper;
import io.openware.platform.order.infra.persistence.mapper.KtvSessionMapper;
import io.openware.platform.order.infra.persistence.po.KtvServerSessionPo;
import io.openware.platform.order.infra.persistence.po.KtvSessionPo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * KTV 会话/服务人员点单仓储适配器的 <b>SQL 形状与列保真</b>（真实适配器 + mock mapper）。
 *
 * <p><b>为什么这个类必须存在</b>：本子域的并发保护**不在 Java 里**——{@code ord_ktv_session} 与
 * {@code ord_ktv_server_session} 都没有 MyBatis-Plus 的 {@code @Version} 保护，工程里也没有
 * {@code OptimisticLockerInnerInterceptor}。唯一的屏障是 {@code KtvSessionMapper#updateWithVersion} /
 * {@code #transferRoom} 与 {@code KtvServerSessionMapper#updateWithVersion} 三条
 * {@code UPDATE ... SET ..., version = version + 1 WHERE id = ? AND version = ?}。
 * 迁移到领域端口时它们被逐字保留，本类把这件事钉死：谁把 {@code AND version = ?} 或 SQL 侧自增
 * 删掉（或把它改回「先读版本再写回」），下面的断言立刻失败。
 *
 * <p><b>第二条（列保真）</b>：原持久化对象 {@code KtvSessionPo} 上有两个
 * {@code @TableField(exist = false)} 的展示字段（{@code elapsedSeconds}/{@code estimatedRoomFee}）。
 * 对 {@code exist = false} 的成员生成列引用会在**运行时**抛
 * {@code can not find lambda cache for this property [...]}，而 {@code test-compile} 完全干净
 * （客户储值适配器真实发生过一次）。本次迁移把它们从持久化对象与领域模型上删除、只留在响应视图里，
 * 因此「非列成员」这一类缺陷在本子域是**结构性不可能**；下面同时断言它不会悄悄回来。
 */
class KtvSessionRepositoryAdapterTest {

  private static final long SESSION_ID = 59L;
  private static final long TENANT_ID = 100L;

  /** 审计四列（记账信息，不是业务事实）：只存在于持久化对象上，不进领域模型。 */
  private static final Set<String> AUDIT_COLUMNS =
      Set.of("createdBy", "createdAt", "updatedBy", "updatedAt");

  @BeforeAll
  static void registerTableInfo() {
    MybatisConfiguration configuration = new MybatisConfiguration();
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
    assistant.setCurrentNamespace(KtvSessionRepositoryAdapterTest.class.getName());
    TableInfoHelper.initTableInfo(assistant, KtvSessionPo.class);
    TableInfoHelper.initTableInfo(assistant, KtvServerSessionPo.class);
  }

  private final KtvSessionMapper sessionMapper = mock(KtvSessionMapper.class);
  private final KtvServerSessionMapper serverSessionMapper = mock(KtvServerSessionMapper.class);
  private final KtvSessionRepositoryAdapter adapter = new KtvSessionRepositoryAdapter(sessionMapper);
  private final KtvServerSessionRepositoryAdapter serverAdapter =
      new KtvServerSessionRepositoryAdapter(serverSessionMapper);

  // ------------------------------------------------------------------ CAS 语句形状

  /**
   * 会话 CAS：{@code WHERE id = ? AND version = ?} + SQL 侧自增，且只写「状态机可变列」。
   *
   * <p>删掉版本条件或把 {@code version = version + 1} 挪到 Java 里都会让这条断言失败。
   */
  @Test
  void sessionCasStatementKeepsTheVersionConditionAndSqlSideIncrement() throws Exception {
    String sql = normalized(updateStatement(KtvSessionMapper.class, "updateWithVersion", KtvSessionPo.class));

    assertTrue(sql.contains("WHERE id = #{id} AND version = #{version}"),
        "CAS 必须带 id + version 条件，实际: " + sql);
    assertTrue(sql.contains("version = version + 1"),
        "版本自增必须写在 SQL 里（先读再写会在并发期间覆盖别人的版本），实际: " + sql);
    // 会话创建后不变的列不得参与 CAS（否则一次状态流转会把归属/预约信息一起改写）。
    assertFalse(sql.contains("tenant_id ="), "CAS 不得改 tenant_id: " + sql);
    assertFalse(sql.contains("order_id ="), "CAS 不得改 order_id: " + sql);
    assertFalse(sql.contains("reserved_start_at ="), "CAS 不得改 reserved_start_at: " + sql);
    assertFalse(sql.contains("reserved_end_at ="), "CAS 不得改 reserved_end_at: " + sql);
    // 状态机可变列必须在。
    assertTrue(sql.contains("status = #{status}"), sql);
    assertTrue(sql.contains("opened_at = #{openedAt}"), sql);
    assertTrue(sql.contains("closed_at = #{closedAt}"), sql);
    assertTrue(sql.contains("billing_start_at = #{billingStartAt}"), sql);
    assertTrue(sql.contains("paused_seconds = #{pausedSeconds}"), sql);
    assertTrue(sql.contains("billing_rule_snapshot_json = #{billingRuleSnapshotJson}"), sql);
    assertTrue(sql.contains("occupation_id = #{occupationId}"), sql);
    assertTrue(sql.contains("party_size = #{partySize}"), sql);
    assertTrue(sql.contains("server_id = #{serverId}"), sql);
    assertTrue(sql.contains("updated_at = #{updatedAt}"), sql);
  }

  /** 转台同样是条件 UPDATE（换包厢 + 换占用 + 快照），并自增版本。 */
  @Test
  void transferRoomStatementIsConditionalAndBumpsVersionInSql() throws Exception {
    String sql = normalized(updateStatement(KtvSessionMapper.class, "transferRoom", KtvSessionPo.class));

    assertTrue(sql.contains("WHERE id = #{id} AND version = #{version}"), sql);
    assertTrue(sql.contains("version = version + 1"), sql);
    assertTrue(sql.contains("room_resource_id = #{roomResourceId}"), sql);
    assertTrue(sql.contains("occupation_id = #{occupationId}"), sql);
    assertTrue(sql.contains("room_name_snapshot = #{roomNameSnapshot}"), sql);
    // 转台只换包厢，计时相关列不得被这次写入重置（计时继承不重置）。
    assertFalse(sql.contains("billing_start_at ="), "转台不得重置计费起点: " + sql);
    assertFalse(sql.contains("paused_seconds ="), "转台不得重置累计暂停: " + sql);
  }

  /** 服务人员点单 CAS：同样条件 + SQL 侧自增，且只写状态/时刻/时长/金额。 */
  @Test
  void serverSessionCasStatementKeepsTheVersionConditionAndSqlSideIncrement() throws Exception {
    String sql = normalized(
        updateStatement(KtvServerSessionMapper.class, "updateWithVersion", KtvServerSessionPo.class));

    assertTrue(sql.contains("WHERE id = #{id} AND version = #{version}"), sql);
    assertTrue(sql.contains("version = version + 1"), sql);
    assertTrue(sql.contains("status = #{status}"), sql);
    assertTrue(sql.contains("duration_seconds = #{durationSeconds}"), sql);
    assertTrue(sql.contains("total_amount = #{totalAmount}"), sql);
    assertFalse(sql.contains("price_per_inc ="), "CAS 不得改单价快照: " + sql);
    assertFalse(sql.contains("ktv_session_id ="), "CAS 不得改包厢会话归属: " + sql);
  }

  /** 服务人员快照回写用 COALESCE 保留既有名称：资源服务抖动不得把已固化的名字清空。 */
  @Test
  void serverSnapshotStatementKeepsExistingNameWhenTheNewOneIsNull() throws Exception {
    String sql = normalized(updateStatement(KtvSessionMapper.class, "updateServerSnapshot", Long.class, Long.class,
        String.class, LocalDateTime.class));

    assertTrue(sql.contains("server_name = COALESCE(#{serverName}, server_name)"),
        "名称缺失时必须保留既有值，实际: " + sql);
    assertTrue(sql.contains("WHERE id = #{id}"), sql);
    // 这条语句不参与状态机 CAS（只写两个快照列 + 更新时间），与状态流转互不覆盖。
    assertFalse(sql.contains("version = version + 1"), sql);
  }

  // ------------------------------------------------------------------ 冲突信号不得被吞

  /** 受影响 0 行 = 并发冲突：适配器必须原样返回 false，服务据此抛 SESSION_VERSION_CONFLICT。 */
  @Test
  void sessionCasReturnsFalseWhenZeroRowsAffected() {
    when(sessionMapper.updateWithVersion(any(KtvSessionPo.class))).thenReturn(0);
    when(sessionMapper.transferRoom(any(KtvSessionPo.class))).thenReturn(0);

    assertFalse(adapter.updateWithVersion(session()), "0 行必须返回 false，不得当成成功");
    assertFalse(adapter.transferRoom(session()));
  }

  /** 受影响 1 行 = 本次写入生效。 */
  @Test
  void sessionCasReturnsTrueWhenOneRowAffected() {
    when(sessionMapper.updateWithVersion(any(KtvSessionPo.class))).thenReturn(1);

    assertTrue(adapter.updateWithVersion(session()));
  }

  @Test
  void serverSessionCasReturnsFalseWhenZeroRowsAffected() {
    when(serverSessionMapper.updateWithVersion(any(KtvServerSessionPo.class))).thenReturn(0);

    assertFalse(serverAdapter.updateWithVersion(serverSession()));
  }

  // ------------------------------------------------------------------ 审计列由适配器负责

  /** 新增：version 兜底 0，审计时间由适配器填；主键由 mapper 回填后写进返回的聚合。 */
  @Test
  void insertFillsVersionAndAuditTimestampsAndReturnsTheGeneratedId() {
    when(sessionMapper.insert(any(KtvSessionPo.class))).thenAnswer(inv -> {
      KtvSessionPo po = inv.getArgument(0);
      po.setId(SESSION_ID);
      return 1;
    });

    KtvSession inserted = adapter.insert(session().withId(null).withVersion(null));

    ArgumentCaptor<KtvSessionPo> captor = ArgumentCaptor.forClass(KtvSessionPo.class);
    verify(sessionMapper).insert(captor.capture());
    KtvSessionPo written = captor.getValue();
    assertEquals(0, written.getVersion(), "version 必须由适配器兜底为 0");
    assertNotNull(written.getCreatedAt(), "created_at 必须由适配器在落库时填充");
    assertNotNull(written.getUpdatedAt(), "updated_at 必须由适配器在落库时填充");
    assertEquals(SESSION_ID, inserted.id(), "生成的主键必须回填进返回的聚合（记录不可变 → withId）");
  }

  /** CAS 写入刷新 {@code updated_at}，且审计值不是端口的参数（由适配器在同一语句里给出）。 */
  @Test
  void casRefreshesUpdatedAtWithoutTakingItAsAPortParameter() {
    when(sessionMapper.updateWithVersion(any(KtvSessionPo.class))).thenReturn(1);

    adapter.updateWithVersion(session());

    ArgumentCaptor<KtvSessionPo> captor = ArgumentCaptor.forClass(KtvSessionPo.class);
    verify(sessionMapper).updateWithVersion(captor.capture());
    assertNotNull(captor.getValue().getUpdatedAt(), "updated_at 必须由适配器刷新");
  }

  /** 服务人员快照回写：端口只给三个业务参数，{@code updated_at} 由适配器补。 */
  @Test
  void serverSnapshotWriteSuppliesUpdatedAtItself() {
    adapter.updateServerSnapshot(20L, 30L, null);

    verify(sessionMapper).updateServerSnapshot(eq(20L), eq(30L), org.mockito.ArgumentMatchers.isNull(),
        any(LocalDateTime.class));
  }

  // ------------------------------------------------------------------ 列保真

  /**
   * <b>本类最要紧的一条</b>：持久化对象与领域模型上都没有非列成员。
   *
   * <p>两个展示字段（{@code elapsedSeconds}/{@code estimatedRoomFee}）已移到响应视图
   * {@code KtvSessionView}：它们不出现在表映射里（否则写入路径可能生成列引用，
   * 运行时抛 {@code can not find lambda cache for this property}），但必须还在对外契约上。
   */
  @Test
  void projectionFieldsLiveOnlyOnTheResponseView() throws Exception {
    TableInfo info = TableInfoHelper.getTableInfo(KtvSessionPo.class);
    assertNotNull(info, "KtvSessionPo 的 TableInfo 必须已注册（列集合断言的前提）");
    Set<String> columnProperties = info.getFieldList().stream()
        .map(TableFieldInfo::getProperty).collect(Collectors.toSet());
    Set<String> nonColumnProperties = new LinkedHashSet<>();
    for (Field field : KtvSessionPo.class.getDeclaredFields()) {
      TableField annotation = field.getAnnotation(TableField.class);
      if (annotation != null && !annotation.exist()) {
        nonColumnProperties.add(field.getName());
      }
    }
    assertEquals(Set.of(), nonColumnProperties,
        "持久化对象上不应再有非列成员（它们已移到响应视图）");
    assertFalse(columnProperties.contains("elapsedSeconds"), "elapsedSeconds 不是列");
    assertFalse(columnProperties.contains("estimatedRoomFee"), "estimatedRoomFee 不是列");

    Set<String> domainComponents = Arrays.stream(KtvSession.class.getRecordComponents())
        .map(component -> component.getName()).collect(Collectors.toSet());
    assertFalse(domainComponents.contains("elapsedSeconds"),
        "读投影不得进入领域模型（进了就可能被写入路径引用）");
    assertFalse(domainComponents.contains("estimatedRoomFee"), "同上");
    assertFalse(domainComponents.contains("createdAt"), "审计列不得进入领域模型");
    assertFalse(domainComponents.contains("updatedAt"), "审计列不得进入领域模型");

    Set<String> viewComponents = Arrays.stream(KtvSessionView.class.getRecordComponents())
        .map(component -> component.getName()).collect(Collectors.toSet());
    assertTrue(viewComponents.contains("elapsedSeconds"), "读投影必须在响应视图上（对外契约不变）");
    assertTrue(viewComponents.contains("estimatedRoomFee"), "同上");
  }

  /** 服务人员点单的持久化对象 26 列全是列：领域模型与表逐列对应，同样没有非列成员。 */
  @Test
  void serverSessionPoHasNoNonColumnMembers() throws Exception {
    TableInfo info = TableInfoHelper.getTableInfo(KtvServerSessionPo.class);
    assertNotNull(info);
    Set<String> columnProperties = info.getFieldList().stream()
        .map(TableFieldInfo::getProperty).collect(Collectors.toSet());
    for (Field field : KtvServerSessionPo.class.getDeclaredFields()) {
      TableField annotation = field.getAnnotation(TableField.class);
      if (annotation != null && !annotation.exist()) {
        throw new AssertionError("服务人员点单的持久化对象不应有非列成员: " + field.getName());
      }
    }
    Set<String> domainComponents = Arrays.stream(KtvServerSession.class.getRecordComponents())
        .map(component -> component.getName()).collect(Collectors.toSet());
    // MyBatis-Plus 的 fieldList 不含主键列（它单独挂在 TableInfo 的 keyProperty 上），因此单独核对主键。
    assertEquals("id", info.getKeyProperty(), "主键列必须是 id");
    assertTrue(info.getKeyColumn().equalsIgnoreCase("id"), "主键列名必须是 id");
    Set<String> mappedProperties = new LinkedHashSet<>(columnProperties);
    mappedProperties.add(info.getKeyProperty());
    // 领域模型不含审计四列（记账信息，不是业务事实，standards/10）：表列 = 领域成员 + 这四个。
    Set<String> expectedProperties = new LinkedHashSet<>(domainComponents);
    expectedProperties.addAll(AUDIT_COLUMNS);
    assertEquals(expectedProperties, mappedProperties,
        "领域模型的成员集合 + 审计四列 必须与表的列集合逐一对应，缺: " + expectedProperties.stream()
            .filter(name -> !mappedProperties.contains(name)).toList()
            + "，多: " + mappedProperties.stream()
            .filter(name -> !expectedProperties.contains(name)).toList());
  }

  // ------------------------------------------------------------------ 查询口径

  /** 跨租户鉴别只读 {@code tenant_id} 一列，且忽略租户行过滤（区分「不存在」与「跨租户」）。 */
  @Test
  void findTenantIdByIdGoesThroughTheDedicatedStatement() {
    when(sessionMapper.selectTenantIdById(SESSION_ID)).thenReturn(TENANT_ID);

    assertEquals(TENANT_ID, adapter.findTenantIdById(SESSION_ID));
    // 空主键不查库（避免一次无意义查询）。
    assertNull(adapter.findTenantIdById(null));
  }

  /**
   * 报表口径：按租户 + 开台时间**半开区间**过滤，条件直接落在 {@code opened_at} 列上（不用函数包裹）。
   */
  @Test
  void findOpenedBetweenFiltersTenantAndOpenedAtHalfOpenRange() {
    when(sessionMapper.selectList(any())).thenReturn(List.of(po()));

    LocalDateTime from = LocalDateTime.of(2026, 9, 20, 0, 0);
    LocalDateTime to = from.plusDays(1);
    List<KtvSession> sessions = adapter.findOpenedBetween(TENANT_ID, from, to);

    assertEquals(1, sessions.size());
    LambdaQueryWrapper<KtvSessionPo> wrapper = capturedSelect();
    String sql = evaluatedSql(wrapper);
    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(sql.contains("TENANT_ID = ?"), "必须按租户收口: " + sql);
    assertTrue(sql.contains("OPENED_AT >= ?") && sql.contains("OPENED_AT < ?"),
        "开台时间必须是半开区间且落在列上（不用函数包裹）: " + sql);
    assertTrue(containsNumber(params, TENANT_ID), "租户条件必须进参数表: " + params.values());
  }

  /** 批量按订单取会话：空集合不下推（会拼出非法的 {@code IN ()}），直接返回空。 */
  @Test
  void listByOrderIdsShortCircuitsOnEmptyInput() {
    assertTrue(adapter.listByOrderIds(List.of()).isEmpty());
    assertTrue(adapter.listByOrderIds(null).isEmpty());
    assertTrue(adapter.listByOrderId(null).isEmpty());

    verify(sessionMapper, never()).selectList(any());
  }

  /** 领域模型转换丢弃审计列：读出来的聚合里没有记账信息。 */
  @Test
  void toDomainDropsAuditColumns() {
    KtvSessionPo po = po();
    po.setCreatedBy(9L);
    po.setCreatedAt(LocalDateTime.of(2026, 9, 20, 10, 0));
    po.setUpdatedBy(9L);
    po.setUpdatedAt(LocalDateTime.of(2026, 9, 20, 11, 0));

    KtvSession session = KtvSessionRepositoryAdapter.toDomain(po);

    assertEquals(SESSION_ID, session.id());
    assertEquals("OPEN", session.status());
    assertEquals(3, session.version());
    assertEquals(25, KtvSession.class.getRecordComponents().length, "领域模型列集合变了：请同步复核");
  }

  // ---------------------------------------------------------------- helpers

  /** 读取 mapper 方法与它的 {@code @Update} 语句（本子域的并发语句是注解 SQL，不是 Wrapper）。 */
  private static String updateStatement(Class<?> mapperType, String method, Class<?>... parameterTypes)
      throws Exception {
    Method target = mapperType.getMethod(method, parameterTypes);
    Update annotation = target.getAnnotation(Update.class);
    assertNotNull(annotation, method + " 必须是一条显式 @Update 语句（并发语义不能藏在 Wrapper 里）");
    assertEquals(1, annotation.value().length, method + " 只应有一条 SQL");
    return annotation.value()[0];
  }

  private static String normalized(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  private static String evaluatedSql(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper) {
    return wrapper.getTargetSql().replaceAll("\\s+", " ").trim().toUpperCase();
  }

  /** 求值后的参数表（先触发 SQL 求值，再读参数——{@code eq} 的值是惰性进参数表的）。 */
  private static Map<String, Object> evaluatedParams(
      com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper) {
    evaluatedSql(wrapper);
    return wrapper.getParamNameValuePairs();
  }

  /** 条件值按 Java 类型装箱（Integer vs Long），必须按数值比较。 */
  private static boolean containsNumber(Map<String, Object> params, long expected) {
    return params.values().stream()
        .anyMatch(value -> value instanceof Number number && number.longValue() == expected);
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<KtvSessionPo> capturedSelect() {
    ArgumentCaptor<LambdaQueryWrapper<KtvSessionPo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(sessionMapper).selectList(captor.capture());
    return captor.getValue();
  }

  private static KtvSession session() {
    return new KtvSession(SESSION_ID, TENANT_ID, 100L, "KTV", 73L, 3001L, 99L, "豪华包 XL02", "XL02", 4, 9L,
        "小美", LocalDateTime.of(2026, 9, 20, 9, 0), LocalDateTime.of(2026, 9, 20, 12, 0),
        LocalDateTime.of(2026, 9, 20, 10, 0), null, "HOUR", LocalDateTime.of(2026, 9, 20, 10, 0), 0, 0, null,
        new BigDecimal("1.5"), null, "OPEN", 3);
  }

  private static KtvSessionPo po() {
    KtvSessionPo po = new KtvSessionPo();
    po.setId(SESSION_ID);
    po.setTenantId(TENANT_ID);
    po.setStoreId(100L);
    po.setBusinessType("KTV");
    po.setOrderId(73L);
    po.setRoomResourceId(3001L);
    po.setRoomNameSnapshot("豪华包 XL02");
    po.setStatus("OPEN");
    po.setVersion(3);
    return po;
  }

  private static KtvServerSession serverSession() {
    return new KtvServerSession(1L, TENANT_ID, 100L, "KTV", 73L, 59L, 9L, 40L, LocalDateTime.now(), null, null,
        "HOUR", 30, "CONSUMER_FAVOR", 5000L, null, null, null, "CNY", null, "ORDERED", 0);
  }

  /** 按主键取会话：空主键不查库；命中时映射成领域模型（读路径的返回类型是领域对象，不是持久化对象）。 */
  @Test
  void findByIdReturnsEmptyForNullIdAndMapsTheRow() {
    assertEquals(Optional.empty(), adapter.findById(null));
    when(sessionMapper.selectById(SESSION_ID)).thenReturn(po());

    Optional<KtvSession> found = adapter.findById(SESSION_ID);

    assertTrue(found.isPresent());
    assertEquals("OPEN", found.get().status());
  }
}
