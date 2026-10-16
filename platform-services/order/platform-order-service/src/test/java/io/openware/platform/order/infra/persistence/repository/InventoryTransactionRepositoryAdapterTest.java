package io.openware.platform.order.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.order.domain.inventory.model.InventoryTransaction;
import io.openware.platform.order.domain.inventory.repository.InventoryTransactionRepository;
import io.openware.platform.order.infra.persistence.mapper.InventoryTransactionMapper;
import io.openware.platform.order.infra.persistence.po.InventoryTransactionPo;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 流水仓储适配器的 SQL 形状与列保真（真实适配器 + mock mapper）。
 *
 * <p>守住的契约：
 * <ul>
 *   <li><b>只追加</b>：本适配器只有 insert，没有 update/delete，端口形状就是这条结构保证；</li>
 *   <li>幂等键查询按 (租户, 幂等键) 定位且 {@code LIMIT 1}；空键不查库；</li>
 *   <li>回补取价按「同一来源明细最近一次出库结转」定位：{@code transaction_type IN (CONSUME, ADJUST_OUT)}
 *       + {@code ORDER BY id DESC LIMIT 1}；</li>
 *   <li>分页 = 同口径 COUNT + 一页记录（id 倒序、LIMIT/OFFSET），关键字命中的物料 id 白名单用 {@code IN}
 *       且空集合不拼 {@code IN ()}；</li>
 *   <li>列集合只有真实列（该 PO 上没有展示字段），成本三列（{@code unit_cost}/{@code total_cost}/
 *       {@code currency_code}）必须真的写进去。</li>
 * </ul>
 */
class InventoryTransactionRepositoryAdapterTest {

  private static final long TENANT_ID = 1L;
  private static final long STORE_ID = 3L;
  private static final long MATERIAL_ID = 10L;
  private static final String KEY = "order-item:7:consume";

  @BeforeAll
  static void registerTableInfo() {
    MybatisConfiguration configuration = new MybatisConfiguration();
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
    assistant.setCurrentNamespace(InventoryTransactionRepositoryAdapterTest.class.getName());
    TableInfoHelper.initTableInfo(assistant, InventoryTransactionPo.class);
  }

  private final InventoryTransactionMapper mapper = mock(InventoryTransactionMapper.class);
  private final InventoryTransactionRepositoryAdapter adapter = new InventoryTransactionRepositoryAdapter(mapper);

  // ------------------------------------------------- 列集合与只追加

  /** 流水表的列集合只有真实列；成本三列必须是真列。 */
  @Test
  void tableInfoHasOnlyRealColumns() {
    assertNotNull(TableInfoHelper.getTableInfo(InventoryTransactionPo.class),
        "InventoryTransactionPo 的 TableInfo 必须已注册（SQL 形状断言的前提）");

    assertEquals(Set.of(), declaredNonColumnProperties(InventoryTransactionPo.class),
        "流水表没有展示字段；一旦新增，写入路径的列集合必须同步复核");
  }

  /** 追加只写业务列与成本列；审计列由 DDL 默认值填充（迁移前的实现同样没写）。 */
  @Test
  void appendWritesBusinessAndCostColumns() {
    when(mapper.insert(any(InventoryTransactionPo.class))).thenReturn(1);
    LocalDateTime occurredAt = LocalDateTime.of(2026, 9, 19, 20, 0, 0);

    InventoryTransaction stored = adapter.append(transaction(occurredAt));

    ArgumentCaptor<InventoryTransactionPo> captor = ArgumentCaptor.forClass(InventoryTransactionPo.class);
    verify(mapper).insert(captor.capture());
    InventoryTransactionPo written = captor.getValue();
    assertEquals(TENANT_ID, written.getTenantId());
    assertEquals(MATERIAL_ID, written.getMaterialId());
    assertEquals("CONSUME", written.getTransactionType());
    assertEquals(0, written.getQuantityDelta().compareTo(new BigDecimal("-4")));
    assertEquals(0, written.getUnitCost().compareTo(new BigDecimal("300")), "出库单价 = 结转的平均成本");
    assertEquals(0, written.getTotalCost().compareTo(new BigDecimal("-1200")), "发生额带符号");
    assertEquals("CNY", written.getCurrencyCode(), "成本币种快照必须与成本同行");
    assertEquals(KEY, written.getIdempotencyKey());
    assertEquals(occurredAt, written.getCreatedAt(), "发生时间原样落库");
    assertEquals(occurredAt, stored.createdAt());
  }

  /** 发生时间为 null 时由适配器兜底为当前时刻（该列 NOT NULL）。 */
  @Test
  void appendFillsTheOccurrenceTimeWhenAbsent() {
    when(mapper.insert(any(InventoryTransactionPo.class))).thenReturn(1);

    InventoryTransaction stored = adapter.append(transaction(null));

    ArgumentCaptor<InventoryTransactionPo> captor = ArgumentCaptor.forClass(InventoryTransactionPo.class);
    verify(mapper).insert(captor.capture());
    assertNotNull(captor.getValue().getCreatedAt(), "created_at（发生时间）是 NOT NULL，必须兜底");
    assertNotNull(stored.createdAt());
  }

  /** 历史行的成本三列是 NULL：null 不写列，落库即 NULL，不用 0 冒充真实成本。 */
  @Test
  void appendKeepsNullCostColumnsForLegacyRows() {
    when(mapper.insert(any(InventoryTransactionPo.class))).thenReturn(1);

    adapter.append(new InventoryTransaction(null, TENANT_ID, STORE_ID, MATERIAL_ID, "RECEIPT", BigDecimal.ONE,
        BigDecimal.ZERO, BigDecimal.ONE, null, null, "USD", "RECEIPT", null, KEY + "-legacy", "历史行",
        LocalDateTime.now()));

    ArgumentCaptor<InventoryTransactionPo> captor = ArgumentCaptor.forClass(InventoryTransactionPo.class);
    verify(mapper).insert(captor.capture());
    assertNull(captor.getValue().getUnitCost());
    assertNull(captor.getValue().getTotalCost());
  }

  // ---------------------------------------------------------------- 读取

  /** 幂等键查询：必须限定租户（幂等键只在租户内唯一）。 */
  @Test
  void findByIdempotencyQueriesTenantAndKey() {
    when(mapper.findByIdempotency(TENANT_ID, KEY)).thenReturn(po());

    assertTrue(adapter.findByIdempotency(TENANT_ID, KEY).isPresent());
    verify(mapper).findByIdempotency(TENANT_ID, KEY);
  }

  /** 空/空白幂等键不查库（避免空串匹配到别人的流水）。 */
  @Test
  void findByIdempotencyShortCircuitsOnBlankKey() {
    assertTrue(adapter.findByIdempotency(TENANT_ID, null).isEmpty());
    assertTrue(adapter.findByIdempotency(TENANT_ID, "  ").isEmpty());
    verify(mapper, never()).findByIdempotency(any(), any());
  }

  /** 回补取价：按同一来源明细的最近一次出库结转（类型与排序口径由 mapper 的语句保证）。 */
  @Test
  void findLatestOutboundDelegatesToTheNarrowQuery() {
    when(mapper.findLatestOutbound(TENANT_ID, STORE_ID, MATERIAL_ID, "ORDER_ITEM", "7")).thenReturn(po());

    assertTrue(adapter.findLatestOutbound(TENANT_ID, STORE_ID, MATERIAL_ID, "ORDER_ITEM", "7").isPresent());
    verify(mapper).findLatestOutbound(TENANT_ID, STORE_ID, MATERIAL_ID, "ORDER_ITEM", "7");
  }

  /** 查不到回 {@code Optional.empty()}（应用层据此退回物料采购价）。 */
  @Test
  void findLatestOutboundReturnsEmptyWhenNoRow() {
    when(mapper.findLatestOutbound(any(), any(), any(), any(), any())).thenReturn(null);

    assertTrue(adapter.findLatestOutbound(TENANT_ID, STORE_ID, MATERIAL_ID, "ORDER_ITEM", "7").isEmpty());
  }

  // ---------------------------------------------------------------- 分页

  /** 分页 = 同口径 COUNT + 一页记录：id 倒序、LIMIT/OFFSET 由偏移与上限翻译而来。 */
  @Test
  void findPageCountsAndFetchesOneWindowOrderedByIdDesc() {
    when(mapper.selectCount(any())).thenReturn(3L);
    when(mapper.selectList(any())).thenReturn(List.of(po()));

    InventoryTransactionRepository.TransactionPage page =
        adapter.findPage(query(20L, 10L, "CONSUME", "ORDER_ITEM", null, List.of(MATERIAL_ID)));

    assertEquals(3L, page.total());
    assertEquals(1, page.transactions().size());
    String sql = evaluatedSql(capturedList());
    assertTrue(sql.contains("ORDER BY ID DESC"), "排序必须是 id desc: " + sql);
    assertTrue(sql.contains("LIMIT 10"), "上限必须落到 SQL: " + sql);
    assertTrue(sql.contains("OFFSET 20"), "偏移必须落到 SQL: " + sql);
    assertTrue(sql.contains("TRANSACTION_TYPE = ?") && sql.contains("SOURCE_TYPE = ?"),
        "类型/来源过滤必须在取页与计数里同口径: " + sql);
    assertTrue(sql.contains("MATERIAL_ID IN"), "命中的物料 id 白名单必须 IN: " + sql);
  }

  /** 命中为空时不再查列表（与「总数为 0 不下发 LIMIT 查询」同一效果）。 */
  @Test
  void findPageSkipsTheWindowQueryWhenNoRowMatches() {
    when(mapper.selectCount(any())).thenReturn(0L);

    assertEquals(0L, adapter.findPage(query(0L, 10L, null, null, null, null)).total());
    verify(mapper, never()).selectList(any());
  }

  /** 时间区间落在 {@code created_at} 上（本表没有 occurred_at 列）；未传的筛选不下发。 */
  @Test
  void findPageAppliesTheClosedCreatedAtRangeAndOmitsAbsentFilters() {
    when(mapper.selectCount(any())).thenReturn(1L);
    when(mapper.selectList(any())).thenReturn(List.of(po()));
    LocalDateTime from = LocalDateTime.of(2026, 9, 1, 0, 0);
    LocalDateTime to = LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000);

    adapter.findPage(new InventoryTransactionRepository.TransactionQuery(TENANT_ID, STORE_ID, null, null, null,
        from, to, null, 0L, 10L));

    LambdaQueryWrapper<InventoryTransactionPo> wrapper = capturedList();
    String sql = evaluatedSql(wrapper);
    assertTrue(sql.contains("CREATED_AT >= ?") && sql.contains("CREATED_AT <= ?"),
        "时间条件必须落在 created_at 上: " + sql);
    assertFalse(sql.contains("TRANSACTION_TYPE = ?"), "未传类型不得进 WHERE: " + sql);
    assertFalse(sql.contains("SOURCE_TYPE = ?"), "未传来源不得进 WHERE: " + sql);
    assertFalse(sql.contains("MATERIAL_ID"), "未传物料也不得进白名单条件: " + sql);
    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(params.containsValue(from) && params.containsValue(to), "区间端点必须原样下发: " + params.values());
  }

  /** 空物料白名单不得拼出 {@code IN ()}（非法 SQL）——应用层在进入端口前就已返回空页。 */
  @Test
  void findPageOmitsEmptyMaterialIdWhitelist() {
    when(mapper.selectCount(any())).thenReturn(1L);
    when(mapper.selectList(any())).thenReturn(List.of(po()));

    adapter.findPage(query(0L, 10L, null, null, null, List.of()));

    assertFalse(evaluatedSql(capturedList()).contains("MATERIAL_ID IN"),
        "空集合不得拼 IN ()；空页由应用层提前返回");
  }

  // ------------------------------------------------------------- helpers

  private static InventoryTransactionRepository.TransactionQuery query(long offset, long limit,
      String transactionType, String sourceType, LocalDateTime unusedFrom, List<Long> materialIds) {
    return new InventoryTransactionRepository.TransactionQuery(TENANT_ID, STORE_ID, null, transactionType,
        sourceType, null, null, materialIds, offset, limit);
  }

  private static InventoryTransaction transaction(LocalDateTime createdAt) {
    return new InventoryTransaction(null, TENANT_ID, STORE_ID, MATERIAL_ID, "CONSUME", new BigDecimal("-4"),
        new BigDecimal("10"), new BigDecimal("6"), new BigDecimal("300"), new BigDecimal("-1200"), "CNY",
        "ORDER_ITEM", "7", KEY, "加项扣减", createdAt);
  }

  private static InventoryTransactionPo po() {
    InventoryTransactionPo po = new InventoryTransactionPo();
    po.setId(20L);
    po.setTenantId(TENANT_ID);
    po.setStoreId(STORE_ID);
    po.setMaterialId(MATERIAL_ID);
    po.setTransactionType("CONSUME");
    po.setQuantityDelta(new BigDecimal("-4"));
    po.setQuantityBefore(new BigDecimal("10"));
    po.setQuantityAfter(new BigDecimal("6"));
    po.setUnitCost(new BigDecimal("300"));
    po.setTotalCost(new BigDecimal("-1200"));
    po.setCurrencyCode("CNY");
    po.setSourceType("ORDER_ITEM");
    po.setSourceId("7");
    po.setIdempotencyKey(KEY);
    po.setCreatedAt(LocalDateTime.of(2026, 9, 19, 20, 0, 0));
    return po;
  }

  /** PO 上标了 {@code @TableField(exist = false)} 的字段名（反射读注解，不猜）。 */
  private static Set<String> declaredNonColumnProperties(Class<?> type) {
    Set<String> result = new LinkedHashSet<>();
    for (Field field : type.getDeclaredFields()) {
      TableField annotation = field.getAnnotation(TableField.class);
      if (annotation != null && !annotation.exist()) {
        result.add(field.getName());
      }
    }
    return result;
  }

  private static String evaluatedSql(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper) {
    return wrapper.getTargetSql().replaceAll("\\s+", " ").trim().toUpperCase();
  }

  private static Map<String, Object> evaluatedParams(
      com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper) {
    evaluatedSql(wrapper);
    return wrapper.getParamNameValuePairs();
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<InventoryTransactionPo> capturedList() {
    ArgumentCaptor<LambdaQueryWrapper<InventoryTransactionPo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper).selectList(captor.capture());
    return captor.getValue();
  }
}
