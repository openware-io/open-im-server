package io.openware.platform.order.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.repository.OrderQuery;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.repository.Persisted;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
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
 * 订单仓储适配器的 SQL 形状与列保真（真实适配器 + mock mapper）。
 *
 * <p><b>为什么这个类必须存在</b>：本次迁移把「订单的并发写入」从服务里搬进了适配器——
 * 五条条件语句（{@code updateAmountsAndStatusWithVersion} / {@code updateStatusGuarded} /
 * {@code holdIfNotHeld} / {@code unholdIfHeld} / {@code voidIfStatus}）是**乐观锁与挂单/作废并发保护的
 * 唯一判定点**。应用层测试现在只能断言「哪个端口方法被调用」，看不到 SQL；
 * 把 {@code WHERE version = ?} / {@code WHERE status = ?} / {@code hold_at IS NULL} 或
 * {@code version = version + 1} 删掉，{@code test-compile} 依然干净。因此这里分两层钉住：
 * <ol>
 *   <li><b>语句文本</b>：用反射读 {@code OrderMapper} 上的 {@code @Update}，断言每条语句的条件谓词与
 *       SQL 侧自增仍在（语句是手写的，不在 Wrapper 上，只有反射能读）；</li>
 *   <li><b>适配器行为</b>：受影响行数 → {@code boolean}、领域聚合 ↔ 持久化对象的逐列对应、
 *       审计列的落库口径，以及「13 个非列字段绝不进入写入」。</li>
 * </ol>
 * 断言方式：查询条件用 MyBatis-Plus 自己生成的 SQL（{@code getTargetSql()}）求值，
 * 不手写 SQL 比对；条件值按 Java 类型装箱（{@code Integer} vs {@code Long}），数值比较一律走
 * {@code longValue()}。{@code @BeforeAll} 里的 {@code TableInfoHelper.initTableInfo} 不可省：
 * 没有它，lambda 的列名解析是**空操作**，{@code getTargetSql()} 里没有列，断言会「莫名其妙地空」。
 */
class OrderRepositoryAdapterTest {

  private static final long ORDER_ID = 73L;
  private static final long TENANT_ID = 100L;
  private static final LocalDateTime FROM = LocalDateTime.of(2026, 9, 1, 0, 0);
  private static final LocalDateTime TO = LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000);

  /** 原持久化对象上标了 {@code @TableField(exist = false)} 的会话投影字段（DDL 里没有这些列）。 */
  private static final Set<String> NON_COLUMN_FIELDS = Set.of("sessionId", "roomResourceId", "roomName",
      "roomCode", "sessionStatus", "partySize", "serverId", "serverName", "roomElapsedSeconds",
      "roomEstimatedFee", "liveTotalAmount", "sessionOpenedAt", "sessionClosedAt");

  @BeforeAll
  static void registerTableInfo() {
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderPo.class);
  }

  private final OrderMapper mapper = mock(OrderMapper.class);
  private final OrderRepositoryAdapter adapter = new OrderRepositoryAdapter(mapper);

  // ------------------------------------------------------- 非列字段（已发生过一次的缺陷）

  /**
   * 13 个会话投影字段不是列。
   *
   * <p>非列字段集变了就说明「订单聚合是否携带它们」这一前提变了，写入路径的列集合必须重新复核
   * ——这是本类最要紧的一条：对 {@code exist = false} 的字段生成列引用会在**运行时**抛
   * {@code can not find lambda cache for this property [...]}。
   */
  @Test
  void tableInfoRegistersTheThirteenSessionProjectionFieldsAsNonColumns() throws Exception {
    TableInfo info = TableInfoHelper.getTableInfo(OrderPo.class);
    assertNotNull(info, "OrderPo 的 TableInfo 必须已注册（SQL 形状断言的前提）");
    Set<String> columnProperties = info.getFieldList().stream()
        .map(TableFieldInfo::getProperty).collect(Collectors.toSet());
    Set<String> nonColumnProperties = new LinkedHashSet<>();
    for (Field field : OrderPo.class.getDeclaredFields()) {
      TableField annotation = field.getAnnotation(TableField.class);
      if (annotation != null && !annotation.exist()) {
        nonColumnProperties.add(field.getName());
      }
    }

    assertEquals(NON_COLUMN_FIELDS, nonColumnProperties,
        "非列字段集变了：写入路径的列集合必须同步复核");
    for (String property : nonColumnProperties) {
      assertFalse(columnProperties.contains(property),
          property + " 标了 @TableField(exist = false)，绝不能出现在列集合里");
    }
    assertTrue(columnProperties.contains("orderNo"), "order_no 是列");
    assertTrue(columnProperties.contains("holdAt"), "hold_at 是列");
    assertTrue(columnProperties.contains("version"), "version 是列");
  }

  /** 领域模型 → 持久化对象时一个会话投影字段都不设（设了就可能进 INSERT/SET）。 */
  @Test
  void toPoNeverSetsTheSessionProjectionFields() {
    OrderPo po = OrderRepositoryAdapter.toPo(order());

    assertNull(po.getSessionId(), "sessionId 不是列，适配器不得写入");
    assertNull(po.getRoomResourceId(), "roomResourceId 不是列，适配器不得写入");
    assertNull(po.getRoomName(), "roomName 不是列，适配器不得写入");
    assertNull(po.getRoomCode(), "roomCode 不是列，适配器不得写入");
    assertNull(po.getSessionStatus(), "sessionStatus 不是列，适配器不得写入");
    assertNull(po.getPartySize(), "partySize 不是列，适配器不得写入");
    assertNull(po.getServerId(), "serverId 不是列，适配器不得写入");
    assertNull(po.getServerName(), "serverName 不是列，适配器不得写入");
    assertNull(po.getRoomElapsedSeconds(), "roomElapsedSeconds 不是列，适配器不得写入");
    assertNull(po.getRoomEstimatedFee(), "roomEstimatedFee 不是列，适配器不得写入");
    assertNull(po.getLiveTotalAmount(), "liveTotalAmount 不是列，适配器不得写入");
    assertNull(po.getSessionOpenedAt(), "sessionOpenedAt 不是列，适配器不得写入");
    assertNull(po.getSessionClosedAt(), "sessionClosedAt 不是列，适配器不得写入");
  }

  /** 领域模型 → 持久化对象：21 个真实列逐列对应（漏一列就会静默不写）。 */
  @Test
  void toPoMapsEveryRealColumn() {
    OrderPo po = OrderRepositoryAdapter.toPo(order());

    assertEquals(ORDER_ID, po.getId());
    assertEquals(TENANT_ID, po.getTenantId());
    assertEquals(55L, po.getOrganizationId());
    assertEquals(1001L, po.getStoreId());
    assertEquals("O202609190001", po.getOrderNo());
    assertEquals("idem-1", po.getIdempotencyKey());
    assertEquals("KTV", po.getBusinessType());
    assertEquals(99L, po.getCustomerId());
    assertEquals("SERVING", po.getStatus());
    assertEquals("CNY", po.getCurrencyCode());
    assertEquals(0, new BigDecimal("10000").compareTo(po.getSubtotalAmount()));
    assertEquals(0, new BigDecimal("500").compareTo(po.getDiscountAmount()));
    assertEquals(0, new BigDecimal("300").compareTo(po.getTaxAmount()));
    assertEquals(0, new BigDecimal("9800").compareTo(po.getTotalAmount()));
    assertEquals(0, new BigDecimal("9800").compareTo(po.getPaidAmount()));
    assertEquals(0, new BigDecimal("9800").compareTo(po.getRefundableAmount()));
    assertEquals(LocalDateTime.of(2026, 9, 19, 22, 0), po.getCompletedAt());
    assertEquals(LocalDateTime.of(2026, 9, 19, 21, 0), po.getCancelledAt());
    assertEquals("等客人回来", po.getHoldReason());
    assertEquals(LocalDateTime.of(2026, 9, 19, 20, 30), po.getHoldAt());
    assertEquals(3, po.getVersion());
  }

  // ------------------------------------------------------------------ 读取

  /** 空 id 不查库（零额视图/未落库聚合的 id 就是 null，不能把它当成一次查询）。 */
  @Test
  void findByIdShortCircuitsOnNullId() {
    assertTrue(adapter.findById(null).isEmpty());
  }

  /** 单行读回：只取业务列，审计四列不进行为聚合，但会随记账快照回给上层。 */
  @Test
  void findByIdMapsBusinessColumnsOnly() {
    when(mapper.selectById(ORDER_ID)).thenReturn(po(ORDER_ID));

    Order order = adapter.findById(ORDER_ID).orElseThrow();

    assertEquals(ORDER_ID, order.id());
    assertEquals("SERVING", order.status());
    assertEquals(3, order.version());
    assertEquals(0, new BigDecimal("9800").compareTo(order.total()));
  }

  /** 批量读：空集合不下推（否则拼出非法的 {@code IN ()}），有值时逐行映射。 */
  @Test
  void findByIdsShortCircuitsOnEmptyAndMapsRows() {
    assertTrue(adapter.findByIds(List.of()).isEmpty());
    assertTrue(adapter.findByIds(null).isEmpty());

    when(mapper.selectByIds(List.of(ORDER_ID))).thenReturn(List.of(po(ORDER_ID)));

    List<Order> orders = adapter.findByIds(List.of(ORDER_ID));

    assertEquals(1, orders.size());
    assertEquals(ORDER_ID, orders.getFirst().id());
  }

  /** 幂等键定位：租户 + 键都要显式落在列上，且只取一行；键为空/空白不查库。 */
  @Test
  void findByIdempotencyKeyFiltersByTenantAndKey() {
    when(mapper.selectOne(any())).thenReturn(po(ORDER_ID));

    assertTrue(adapter.findByIdempotencyKey(TENANT_ID, "idem-1").isPresent());

    LambdaQueryWrapper<OrderPo> wrapper = capturedSelectOne();
    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(containsNumber(params, TENANT_ID), "幂等键只在租户内唯一，必须显式过滤租户: " + params.values());
    assertTrue(containsParam(params, "idem-1"), String.valueOf(params.values()));
    assertTrue(evaluatedSql(wrapper).contains("LIMIT 1"), "只取一行");

    assertTrue(adapter.findByIdempotencyKey(TENANT_ID, "   ").isEmpty());
    assertTrue(adapter.findByIdempotencyKey(null, "idem-1").isEmpty());
  }

  /** 列表读路径：闭区间落在 created_at 上（不用函数包裹）且保持倒序；商户会话不收窄会员。 */
  @Test
  void listAppliesClosedTimeRangeAndDescOrder() {
    when(mapper.selectList(any())).thenReturn(List.of(po(ORDER_ID)));

    List<Persisted<Order>> rows = adapter.list(OrderQuery.newestFirst().from(FROM).until(TO));

    assertEquals(1, rows.size());
    assertEquals("等客人回来", rows.getFirst().aggregate().holdReason());
    assertEquals(9L, rows.getFirst().audit().createdBy(), "created_by 必须随行读回（员工业绩按它归集）");
    assertEquals(LocalDateTime.of(2026, 9, 19, 8, 0), rows.getFirst().audit().createdAt());

    LambdaQueryWrapper<OrderPo> wrapper = capturedSelectList();
    String sql = evaluatedSql(wrapper);
    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(sql.contains("CREATED_AT >= ?"), sql);
    assertTrue(sql.contains("CREATED_AT <= ?"), sql);
    assertFalse(sql.contains("CUSTOMER_ID = ?"), "商户列表不得被收窄到某个客户: " + sql);
    assertFalse(sql.contains("DATE("), "时间列不得被函数包裹，否则索引失效: " + sql);
    assertTrue(sql.contains("ORDER BY CREATED_AT DESC"), "排序口径必须保持倒序: " + sql);
    assertTrue(containsParam(params, FROM), "下界端点必须原样进入条件: " + params.values());
    assertTrue(containsParam(params, TO), "上界端点必须原样进入条件: " + params.values());
  }

  /** 消费者会话的读路径：会员收窄落到 customer_id 列上。 */
  @Test
  void listScopesToCustomerWhenAsked() {
    when(mapper.selectList(any())).thenReturn(List.of());

    adapter.list(OrderQuery.newestFirst().withCustomer(99L));

    LambdaQueryWrapper<OrderPo> wrapper = capturedSelectList();
    String sql = evaluatedSql(wrapper);
    assertTrue(sql.contains("CUSTOMER_ID = ?"), sql);
    assertTrue(containsNumber(evaluatedParams(wrapper), 99L), sql);
  }

  /** 报表口径：营业日是半开的 {@code [当天起点, 次日起点)}（{@code <} 上界），且不排序。 */
  @Test
  void listUsesHalfOpenUpperBoundAndNoOrderingForReports() {
    when(mapper.selectList(any())).thenReturn(List.of());

    adapter.list(OrderQuery.all().withTenant(TENANT_ID).from(FROM).before(TO));

    LambdaQueryWrapper<OrderPo> wrapper = capturedSelectList();
    String sql = evaluatedSql(wrapper);
    assertTrue(sql.contains("TENANT_ID = ?"), sql);
    assertTrue(sql.contains("CREATED_AT >= ?"), sql);
    assertTrue(sql.contains("CREATED_AT < ?"), "报表营业日右开: " + sql);
    assertFalse(sql.contains("CREATED_AT <= ?"), "半开区间不得退化成闭区间: " + sql);
    assertFalse(sql.contains("ORDER BY"), "报表聚合不需要排序: " + sql);
  }

  /** 跨租户鉴别语句：适配器原样委托「忽略租户拦截」的那条查询，并翻成 Optional。 */
  @Test
  void findTenantIdByIdDelegatesToTheTenantIgnoringStatement() {
    when(mapper.selectTenantIdById(ORDER_ID)).thenReturn(TENANT_ID);

    assertEquals(Optional.of(TENANT_ID), adapter.findTenantIdById(ORDER_ID));
    verify(mapper).selectTenantIdById(ORDER_ID);
    assertTrue(adapter.findTenantIdById(null).isEmpty());
  }

  // ------------------------------------------------------------------ 新增与条件写

  /** 新增：业务列 + 审计列（现在 + 操作人）；version 缺省 0；返回带主键的新聚合。 */
  @Test
  void insertStampsAuditAndReturnsAggregateWithGeneratedId() {
    doAnswer(invocation -> {
      ((OrderPo) invocation.getArgument(0)).setId(88L);
      return 1;
    }).when(mapper).insert(any(OrderPo.class));

    Order inserted = adapter.insert(order(null), 42L);

    ArgumentCaptor<OrderPo> captor = ArgumentCaptor.forClass(OrderPo.class);
    verify(mapper).insert(captor.capture());
    OrderPo written = captor.getValue();
    assertEquals("O202609190001", written.getOrderNo());
    assertEquals(0, written.getVersion(), "版本列缺省 0（历史/新建聚合可能没有版本）");
    assertEquals(42L, written.getCreatedBy());
    assertEquals(42L, written.getUpdatedBy());
    assertNotNull(written.getCreatedAt());
    assertNotNull(written.getUpdatedAt());
    assertNull(written.getSessionId(), "新增不得写会话投影字段");
    assertEquals(88L, inserted.id(), "主键必须回填到返回的聚合上");
  }

  /** 金额与状态的条件提交：委托给带 version 条件的那条语句，并刷新 updated_at。 */
  @Test
  void updateAmountsAndStatusWithVersionDelegatesWithVersionAndStampsUpdatedAt() {
    when(mapper.updateAmountsAndStatusWithVersion(any(OrderPo.class), eq(3))).thenReturn(1);

    assertTrue(adapter.updateAmountsAndStatusWithVersion(order(), 3));

    ArgumentCaptor<OrderPo> captor = ArgumentCaptor.forClass(OrderPo.class);
    verify(mapper).updateAmountsAndStatusWithVersion(captor.capture(), eq(3));
    OrderPo written = captor.getValue();
    assertEquals("SERVING", written.getStatus());
    assertEquals(0, new BigDecimal("9800").compareTo(written.getTotalAmount()));
    assertNotNull(written.getUpdatedAt(), "条件语句的 updated_at 来自该字段，不填会写成 NULL");
  }

  /**
   * 五条条件语句的**语句文本**必须保住各自的谓词与 SQL 侧自增。
   *
   * <p>这是本类存在的核心理由：把 {@code AND version = ?}、{@code AND status = ?}、
   * {@code hold_at IS NULL}、{@code hold_at IS NOT NULL} 或 {@code version = version + 1} 删掉，
   * 并发保护就没了，而 {@code test-compile} 完全看不出来。
   */
  @Test
  void conditionalStatementsKeepTheirPredicatesAndSqlSideIncrement() throws Exception {
    String amounts = updateStatement("updateAmountsAndStatusWithVersion", OrderPo.class, int.class);
    assertTrue(amounts.contains("WHERE id = #{po.id} AND version = #{expectedVersion}"),
        "金额提交必须按 id + 期望版本定位: " + amounts);
    assertTrue(normalize(amounts).contains("VERSION=VERSION+1"),
        "版本自增必须写在 SQL 里（Java 侧 +1 会在并发窗口写回过期版本号）: " + amounts);
    for (String column : List.of("subtotal_amount", "discount_amount", "tax_amount", "total_amount",
        "refundable_amount")) {
      assertTrue(amounts.contains(column + " = #{po."), "五个金额列一个都不能漏: " + column + " in " + amounts);
    }
    assertTrue(amounts.contains("status = #{po.status}"), "状态必须与金额同一次写入: " + amounts);

    String status = updateStatement("updateStatusGuarded", Long.class, String.class, String.class,
        LocalDateTime.class);
    assertTrue(status.contains("WHERE id = #{id} AND status = #{expectedStatus}"),
        "状态转换必须按当前状态判定: " + status);

    String hold = updateStatement("holdIfNotHeld", Long.class, LocalDateTime.class, String.class,
        LocalDateTime.class);
    assertTrue(hold.contains("WHERE id = #{id} AND hold_at IS NULL"),
        "挂单判据是 hold_at 而不是 status（挂单不改商业状态）: " + hold);

    String unhold = updateStatement("unholdIfHeld", Long.class, LocalDateTime.class);
    assertTrue(unhold.contains("WHERE id = #{id} AND hold_at IS NOT NULL"),
        "解挂判据与挂单对称: " + unhold);

    String voided = updateStatement("voidIfStatus", Long.class, String.class, LocalDateTime.class);
    assertTrue(voided.contains("SET status = 'VOIDED'"), voided);
    assertTrue(voided.contains("WHERE id = #{id} AND status = #{expectedStatus}"),
        "作废必须按读到的状态判定（重复取消是幂等语义，不能按 version 守卫）: " + voided);
  }

  /** 受影响 0 行 = 期间已被他人写入：五条语句都必须翻成 false，适配器不得吞掉这个信号。 */
  @Test
  void conditionalWritesMapZeroRowsAffectedToFalse() {
    when(mapper.updateAmountsAndStatusWithVersion(any(OrderPo.class), eq(0))).thenReturn(0);
    when(mapper.updateStatusGuarded(any(), any(), any(), any())).thenReturn(0);
    when(mapper.holdIfNotHeld(any(), any(), any(), any())).thenReturn(0);
    when(mapper.unholdIfHeld(any(), any())).thenReturn(0);
    when(mapper.voidIfStatus(any(), any(), any())).thenReturn(0);

    assertFalse(adapter.updateAmountsAndStatusWithVersion(order().withVersion(0), 0));
    assertFalse(adapter.updateStatusGuarded(ORDER_ID, "DRAFT", "WAITING_SETTLEMENT", LocalDateTime.now()));
    assertFalse(adapter.holdIfNotHeld(ORDER_ID, LocalDateTime.now(), "原因", LocalDateTime.now()));
    assertFalse(adapter.unholdIfHeld(ORDER_ID, LocalDateTime.now()));
    assertFalse(adapter.voidIfStatus(ORDER_ID, "SERVING", LocalDateTime.now()));
  }

  /** 受影响 1 行 = 本次写入成功：必须返回 true（调用方据此继续推进状态机）。 */
  @Test
  void conditionalWritesMapOneRowAffectedToTrue() {
    when(mapper.updateStatusGuarded(any(), any(), any(), any())).thenReturn(1);
    when(mapper.holdIfNotHeld(any(), any(), any(), any())).thenReturn(1);
    when(mapper.unholdIfHeld(any(), any())).thenReturn(1);
    when(mapper.voidIfStatus(any(), any(), any())).thenReturn(1);

    assertTrue(adapter.updateStatusGuarded(ORDER_ID, "DRAFT", "WAITING_SETTLEMENT", LocalDateTime.now()));
    assertTrue(adapter.holdIfNotHeld(ORDER_ID, LocalDateTime.now(), "原因", LocalDateTime.now()));
    assertTrue(adapter.unholdIfHeld(ORDER_ID, LocalDateTime.now()));
    assertTrue(adapter.voidIfStatus(ORDER_ID, "SERVING", LocalDateTime.now()));
  }

  // ---------------------------------------------------------------- helpers

  private static Order order() {
    return order(3);
  }

  /** 显式版本（{@code null} = 新建聚合没有版本，插入时必须落 0）。 */
  private static Order order(Integer version) {
    return new Order(ORDER_ID, TENANT_ID, 55L, 1001L, "O202609190001", "idem-1", "KTV", 99L, "SERVING",
        "CNY", new BigDecimal("10000"), new BigDecimal("500"), new BigDecimal("300"),
        new BigDecimal("9800"), new BigDecimal("9800"), new BigDecimal("9800"),
        LocalDateTime.of(2026, 9, 19, 22, 0), LocalDateTime.of(2026, 9, 19, 21, 0), "等客人回来",
        LocalDateTime.of(2026, 9, 19, 20, 30), version);
  }

  private static OrderPo po(Long id) {
    OrderPo po = new OrderPo();
    po.setId(id);
    po.setTenantId(TENANT_ID);
    po.setOrganizationId(55L);
    po.setStoreId(1001L);
    po.setOrderNo("O202609190001");
    po.setIdempotencyKey("idem-1");
    po.setBusinessType("KTV");
    po.setCustomerId(99L);
    po.setStatus("SERVING");
    po.setCurrencyCode("CNY");
    po.setSubtotalAmount(new BigDecimal("10000"));
    po.setDiscountAmount(new BigDecimal("500"));
    po.setTaxAmount(new BigDecimal("300"));
    po.setTotalAmount(new BigDecimal("9800"));
    po.setPaidAmount(new BigDecimal("9800"));
    po.setRefundableAmount(new BigDecimal("9800"));
    po.setCompletedAt(LocalDateTime.of(2026, 9, 19, 22, 0));
    po.setCancelledAt(LocalDateTime.of(2026, 9, 19, 21, 0));
    po.setHoldReason("等客人回来");
    po.setHoldAt(LocalDateTime.of(2026, 9, 19, 20, 30));
    po.setVersion(3);
    po.setCreatedBy(9L);
    po.setCreatedAt(LocalDateTime.of(2026, 9, 19, 8, 0));
    po.setUpdatedBy(9L);
    po.setUpdatedAt(LocalDateTime.of(2026, 9, 19, 9, 30));
    return po;
  }

  /** 读 {@code OrderMapper} 上手写条件语句的文本（Wrapper 断言覆盖不到它们）。 */
  private static String updateStatement(String method, Class<?>... parameters) throws Exception {
    Method target = OrderMapper.class.getMethod(method, parameters);
    Update annotation = target.getAnnotation(Update.class);
    assertNotNull(annotation, method + " 必须仍然是手写 @Update（条件写在 SQL 里）");
    return String.join(" ", annotation.value());
  }

  private static String normalize(String sql) {
    return sql.replace(" ", "").toUpperCase();
  }

  private static String evaluatedSql(AbstractWrapper<?, ?, ?> wrapper) {
    return wrapper.getTargetSql().replaceAll("\\s+", " ").trim().toUpperCase();
  }

  /** 求值后的参数表（先触发 SQL 求值，再读参数——{@code eq} 的值是惰性进参数表的）。 */
  private static Map<String, Object> evaluatedParams(AbstractWrapper<?, ?, ?> wrapper) {
    evaluatedSql(wrapper);
    return wrapper.getParamNameValuePairs();
  }

  /** 条件值按 Java 类型装箱（Integer vs Long），必须按数值比较。 */
  private static boolean containsNumber(Map<String, Object> params, long expected) {
    return params.values().stream()
        .anyMatch(value -> value instanceof Number number && number.longValue() == expected);
  }

  /** {@code eq} 的值是标量、{@code in} 的值是集合，两种都要能命中。 */
  private static boolean containsParam(Map<String, Object> params, Object expected) {
    for (Object value : params.values()) {
      if (value instanceof Collection<?> collection) {
        if (collection.contains(expected)) {
          return true;
        }
      } else if (expected.equals(value)) {
        return true;
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<OrderPo> capturedSelectOne() {
    ArgumentCaptor<LambdaQueryWrapper<OrderPo>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper).selectOne(captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<OrderPo> capturedSelectList() {
    ArgumentCaptor<LambdaQueryWrapper<OrderPo>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper).selectList(captor.capture());
    return captor.getValue();
  }
}
