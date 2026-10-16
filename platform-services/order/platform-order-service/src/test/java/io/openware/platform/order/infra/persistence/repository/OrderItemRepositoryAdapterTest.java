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
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.order.domain.order.model.OrderItem;
import io.openware.platform.order.domain.order.repository.OrderItemQuery;
import io.openware.platform.order.domain.order.repository.OrderItemRepository;
import io.openware.platform.order.domain.order.repository.Persisted;
import io.openware.platform.order.infra.persistence.mapper.OrderItemMapper;
import io.openware.platform.order.infra.persistence.po.OrderItemPo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 订单明细仓储适配器的 SQL 形状与列保真（真实适配器 + mock mapper）。
 *
 * <p>本类钉住三件在应用层看不见、删掉也不会让 {@code test-compile} 报错的事：
 * <ol>
 *   <li><b>两条批量口径不能合并</b>：{@code selectActiveRoomFeeItemsByOrderIds}（仅 ROOM_FEE）与
 *       {@code selectActiveItemsByOrderIds}（全部 ACTIVE）是实时合计差额法的两个输入，
 *       合并会让房费快照要么漏扣、要么把普通加项当成房费扣掉；</li>
 *   <li><b>审批的条件更新</b>：{@code markApproval} 的 {@code AND status = 'PENDING_APPROVAL'} 是
 *       「确认 vs 拒绝」并发的唯一判定点（改成先查再写就回到双写竞态）；</li>
 *   <li><b>审计列口径</b>：插入写 {@code created_at/updated_at}、整实体更新刷新 {@code updated_at}
 *       但不碰 {@code created_at}；待确认聚合与账单的固化时刻都要读回它们。</li>
 * </ol>
 * 条件值按 Java 类型装箱，数值比较一律走 {@code longValue()}；{@code @BeforeAll} 的
 * {@code TableInfoHelper.initTableInfo} 不可省（否则 lambda 列名解析是空操作、断言会「空过」）。
 */
class OrderItemRepositoryAdapterTest {

  private static final long ORDER_ID = 73L;
  private static final long ITEM_ID = 501L;
  private static final long TENANT_ID = 100L;

  @BeforeAll
  static void registerTableInfo() {
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderItemPo.class);
  }

  private final OrderItemMapper mapper = mock(OrderItemMapper.class);
  private final OrderItemRepositoryAdapter adapter = new OrderItemRepositoryAdapter(mapper);

  /** 明细持久化对象上没有非列字段（有的话写入路径就必须重新复核列集合）。 */
  @Test
  void orderItemPoHasNoNonColumnFields() {
    TableInfo info = TableInfoHelper.getTableInfo(OrderItemPo.class);
    assertNotNull(info, "OrderItemPo 的 TableInfo 必须已注册（SQL 形状断言的前提）");
    for (Field field : OrderItemPo.class.getDeclaredFields()) {
      TableField annotation = field.getAnnotation(TableField.class);
      assertTrue(annotation == null || annotation.exist(),
          field.getName() + " 不得是 @TableField(exist = false)：明细的 24 个字段全是真实列");
    }
  }

  /** 已生效明细（账单/金额重算的口径）：order_id + status=ACTIVE，按 id 升序。 */
  @Test
  void activeItemsQueryCarriesOrderAndStatusAndOrdersById() {
    when(mapper.selectList(any())).thenReturn(List.of(po()));

    List<Persisted<OrderItem>> rows = adapter.list(OrderItemQuery.activeOfOrder(ORDER_ID));

    assertEquals(1, rows.size());
    assertEquals("SERVICE", rows.getFirst().aggregate().itemType());
    assertEquals(LocalDateTime.of(2026, 9, 19, 8, 0), rows.getFirst().audit().createdAt(),
        "created_at 必须随行读回（待确认加项要展示提交时间）");

    String sql = evaluatedSql(capturedSelectList());
    Map<String, Object> params = evaluatedParams(capturedSelectList());
    assertTrue(sql.contains("ORDER_ID = ?"), sql);
    assertTrue(sql.contains("STATUS = ?"), sql);
    assertTrue(sql.contains("ORDER BY ID ASC"), sql);
    assertTrue(containsNumber(params, ORDER_ID), String.valueOf(params.values()));
    assertTrue(containsParam(params, OrderItem.STATUS_ACTIVE), String.valueOf(params.values()));
  }

  /** 待确认聚合：租户 + PENDING_APPROVAL，先提交的先处理，并带上限（防极端数据撑爆响应）。 */
  @Test
  void pendingApprovalQueryCarriesTenantStatusOrderAndLimit() {
    when(mapper.selectList(any())).thenReturn(List.of());

    adapter.list(OrderItemQuery.pendingApprovalOfTenant(TENANT_ID, 200));

    String sql = evaluatedSql(capturedSelectList());
    assertTrue(sql.contains("TENANT_ID = ?"), sql);
    assertTrue(sql.contains("STATUS = ?"), sql);
    assertTrue(sql.contains("ORDER BY CREATED_AT ASC") && sql.contains("ID ASC"),
        "先提交的先处理（created_at 再 id，保证同毫秒也有稳定序）: " + sql);
    assertTrue(sql.contains("LIMIT 200"), "裁剪只影响明细条数，计数与金额仍按全量: " + sql);
    assertTrue(containsParam(evaluatedParams(capturedSelectList()), OrderItem.STATUS_PENDING_APPROVAL),
        "待确认口径固定为 PENDING_APPROVAL");
  }

  /** 房费明细定位：订单内唯一的 ROOM_FEE 行（取 1 行，多行会把房费重复计入合计）。 */
  @Test
  void firstOfTypeQueryLimitsToOneRow() {
    when(mapper.selectList(any())).thenReturn(List.of());

    adapter.list(OrderItemQuery.firstOfType(ORDER_ID, "ROOM_FEE"));

    String sql = evaluatedSql(capturedSelectList());
    assertTrue(sql.contains("ORDER_ID = ?"), sql);
    assertTrue(sql.contains("ITEM_TYPE = ?"), sql);
    assertTrue(sql.contains("LIMIT 1"), sql);
    assertTrue(containsParam(evaluatedParams(capturedSelectList()), "ROOM_FEE"), sql);
  }

  /** 库存回补列表：租户 + 订单 + inventory_status=CONSUMED。 */
  @Test
  void consumedItemsQueryCarriesTenantOrderAndInventoryStatus() {
    when(mapper.selectList(any())).thenReturn(List.of());

    adapter.list(OrderItemQuery.consumedOfOrder(TENANT_ID, ORDER_ID));

    String sql = evaluatedSql(capturedSelectList());
    assertTrue(sql.contains("TENANT_ID = ?"), sql);
    assertTrue(sql.contains("ORDER_ID = ?"), sql);
    assertTrue(sql.contains("INVENTORY_STATUS = ?"), sql);
    assertTrue(containsParam(evaluatedParams(capturedSelectList()), "CONSUMED"), sql);
  }

  /**
   * 两条批量口径的**语句文本**必须保持不同（合并会让差额法算错）。
   *
   * <p>房费那条多一个 {@code item_type = 'ROOM_FEE'}；两条都只取 {@code status = 'ACTIVE'}。
   */
  @Test
  void twoBatchStatementsAreDifferentAndKeepTheirPredicates() throws Exception {
    String all = selectStatement("selectActiveItemsByOrderIds", List.class);
    String roomFee = selectStatement("selectActiveRoomFeeItemsByOrderIds", List.class);

    assertTrue(normalize(all).contains("WHERE STATUS = 'ACTIVE'"), all);
    assertFalse(normalize(all).contains("ITEM_TYPE"), "「全部 ACTIVE 明细」不得只看房费: " + all);
    assertTrue(normalize(roomFee).contains("WHERE ITEM_TYPE = 'ROOM_FEE' AND STATUS = 'ACTIVE'"), roomFee);
    assertFalse(normalize(all).equals(normalize(roomFee)), "两条口径不得被合并成一条");
    assertTrue(normalize(all).contains("ORDER BY ORDER_ID, ID"), all);
    assertTrue(normalize(all).contains("ORDER_ID IN"), "批量口径必须用 IN 一次查完（避免 N+1）: " + all);
  }

  /** 空集合不下推（否则拼出非法的 {@code IN ()}），也不可能命中任何行。 */
  @Test
  void batchQueriesShortCircuitOnEmptyCollections() {
    assertTrue(adapter.listActiveByOrderIds(List.of()).isEmpty());
    assertTrue(adapter.listActiveRoomFeeItemsByOrderIds(List.of()).isEmpty());
    assertTrue(adapter.listActiveByOrderIds(null).isEmpty());
  }

  /** 待确认存在性：走 selectCount（不是先把行拉回来），条件固定 order_id + PENDING_APPROVAL。 */
  @Test
  void existsPendingApprovalUsesSelectCount() {
    when(mapper.selectCount(any())).thenReturn(2L);

    assertTrue(adapter.existsPendingApproval(ORDER_ID));

    String sql = evaluatedSql(capturedSelectCount());
    assertTrue(sql.contains("ORDER_ID = ?"), sql);
    assertTrue(containsParam(evaluatedParams(capturedSelectCount()), OrderItem.STATUS_PENDING_APPROVAL), sql);

    when(mapper.selectCount(any())).thenReturn(0L);
    assertFalse(adapter.existsPendingApproval(ORDER_ID));
    assertFalse(adapter.existsPendingApproval(null), "订单为空 = 没有待确认加项，不查库");
  }

  /** 新增：业务列 + 审计列（现在）；返回带主键的新聚合。 */
  @Test
  void insertStampsAuditAndReturnsAggregateWithGeneratedId() {
    doAnswer(invocation -> {
      ((OrderItemPo) invocation.getArgument(0)).setId(ITEM_ID);
      return 1;
    }).when(mapper).insert(any(OrderItemPo.class));

    OrderItem inserted = adapter.insert(item());

    ArgumentCaptor<OrderItemPo> captor = ArgumentCaptor.forClass(OrderItemPo.class);
    verify(mapper).insert(captor.capture());
    OrderItemPo written = captor.getValue();
    assertEquals("SERVICE", written.getItemType());
    assertEquals(0, new BigDecimal("10000").compareTo(written.getTotalAmount()));
    assertNotNull(written.getCreatedAt());
    assertNotNull(written.getUpdatedAt());
    assertEquals(ITEM_ID, inserted.id(), "主键必须回填到返回的聚合上");
  }

  /** 整实体更新：刷新 updated_at（账单要展示房费明细的固化时刻），且**不**写 created_at。 */
  @Test
  void updateRefreshesUpdatedAtWithoutTouchingCreatedAt() {
    adapter.update(item().withId(ITEM_ID));

    ArgumentCaptor<OrderItemPo> captor = ArgumentCaptor.forClass(OrderItemPo.class);
    verify(mapper).updateById(captor.capture());
    OrderItemPo written = captor.getValue();
    assertEquals(ITEM_ID, written.getId());
    assertNotNull(written.getUpdatedAt(), "updated_at 是记账列，由适配器刷成「现在」");
    assertNull(written.getCreatedAt(), "created_at 不参与 SET（留 NULL 表示不覆盖库中原值）");
  }

  /**
   * 审批流转的语句文本与行数翻译：{@code AND status = 'PENDING_APPROVAL'} 与显式租户条件必须在。
   */
  @Test
  void markApprovalKeepsPendingPredicateAndMapsRowsAffected() throws Exception {
    String sql = updateStatement("markApproval", Long.class, Long.class, Long.class, String.class, Long.class);
    String normalized = normalize(sql);
    assertTrue(normalized.contains("SET STATUS = #{STATUS}, UPDATED_BY = #{UPDATEDBY}"), sql);
    assertTrue(normalized.contains("WHERE TENANT_ID = #{TENANTID} AND ORDER_ID = #{ORDERID} AND ID = #{ITEMID}"),
        "定位必须带显式租户 + 订单 + 明细: " + sql);
    assertTrue(normalized.contains("AND STATUS = 'PENDING_APPROVAL'"),
        "只有待确认的加项能被处理（并发判定点）: " + sql);
    assertTrue(normalized.contains("CURRENT_TIMESTAMP(3)"), "更新时间由数据库给，避免应用时钟漂移: " + sql);

    when(mapper.markApproval(TENANT_ID, ORDER_ID, ITEM_ID, OrderItem.STATUS_ACTIVE, 9L)).thenReturn(1);
    when(mapper.markApproval(TENANT_ID, ORDER_ID, ITEM_ID, OrderItem.STATUS_REJECTED, 9L)).thenReturn(0);

    assertTrue(adapter.markApproval(TENANT_ID, ORDER_ID, ITEM_ID, OrderItem.STATUS_ACTIVE, 9L));
    assertFalse(adapter.markApproval(TENANT_ID, ORDER_ID, ITEM_ID, OrderItem.STATUS_REJECTED, 9L),
        "受影响 0 行 = 已被并发处理或状态不符，必须翻成 false");
  }

  /** 单行读回与空 id。 */
  @Test
  void findByIdShortCircuitsOnNullIdAndMapsBusinessColumns() {
    assertTrue(adapter.findById(null).isEmpty());

    when(mapper.selectById(ITEM_ID)).thenReturn(po());

    OrderItem item = adapter.findById(ITEM_ID).orElseThrow();
    assertEquals(ITEM_ID, item.id());
    assertEquals(ORDER_ID, item.orderId());
    assertEquals(OrderItem.STATUS_ACTIVE, item.status());
    assertEquals(0, new BigDecimal("500").compareTo(item.unitPrice()));
  }

  // ---------------------------------------------------------------- helpers

  private static OrderItem item() {
    return new OrderItem(null, TENANT_ID, 1001L, "KTV", ORDER_ID, "SERVICE", 7L, 9L, "服务人员#9",
        new BigDecimal("500"), new BigDecimal("2"), BigDecimal.ZERO, BigDecimal.ZERO,
        new BigDecimal("10000"), "CNY", "{\"serverPricePerInc\":5000}", OrderItem.STATUS_ACTIVE,
        OrderItem.SOURCE_MERCHANT, "CONSUMED", 300L, null, null, null, null);
  }

  private static OrderItemPo po() {
    OrderItemPo po = new OrderItemPo();
    po.setId(ITEM_ID);
    po.setTenantId(TENANT_ID);
    po.setStoreId(1001L);
    po.setBusinessType("KTV");
    po.setOrderId(ORDER_ID);
    po.setItemType("SERVICE");
    po.setCatalogItemId(7L);
    po.setResourceId(9L);
    po.setNameSnapshot("服务人员#9");
    po.setUnitPrice(new BigDecimal("500"));
    po.setQuantity(new BigDecimal("2"));
    po.setDiscountAmount(BigDecimal.ZERO);
    po.setTaxAmount(BigDecimal.ZERO);
    po.setTotalAmount(new BigDecimal("10000"));
    po.setCurrencyCode("CNY");
    po.setPriceSnapshotJson("{\"serverPricePerInc\":5000}");
    po.setStatus(OrderItem.STATUS_ACTIVE);
    po.setSource(OrderItem.SOURCE_MERCHANT);
    po.setInventoryStatus("CONSUMED");
    po.setInventoryMaterialId(300L);
    po.setCreatedBy(9L);
    po.setCreatedAt(LocalDateTime.of(2026, 9, 19, 8, 0));
    po.setUpdatedBy(9L);
    po.setUpdatedAt(LocalDateTime.of(2026, 9, 19, 9, 30));
    return po;
  }

  private static String updateStatement(String method, Class<?>... parameters) throws Exception {
    Method target = OrderItemMapper.class.getMethod(method, parameters);
    Update annotation = target.getAnnotation(Update.class);
    assertNotNull(annotation, method + " 必须仍然是手写 @Update（条件写在 SQL 里）");
    return String.join(" ", annotation.value());
  }

  private static String selectStatement(String method, Class<?>... parameters) throws Exception {
    Method target = OrderItemMapper.class.getMethod(method, parameters);
    Select annotation = target.getAnnotation(Select.class);
    assertNotNull(annotation, method + " 必须仍然是手写 @Select（口径写在 SQL 里）");
    return String.join(" ", annotation.value());
  }

  private static String normalize(String sql) {
    return sql.replaceAll("\\s+", " ").trim().toUpperCase();
  }

  private static String evaluatedSql(AbstractWrapper<?, ?, ?> wrapper) {
    return wrapper.getTargetSql().replaceAll("\\s+", " ").trim().toUpperCase();
  }

  private static Map<String, Object> evaluatedParams(AbstractWrapper<?, ?, ?> wrapper) {
    evaluatedSql(wrapper);
    return wrapper.getParamNameValuePairs();
  }

  private static boolean containsNumber(Map<String, Object> params, long expected) {
    return params.values().stream()
        .anyMatch(value -> value instanceof Number number && number.longValue() == expected);
  }

  private static boolean containsParam(Map<String, Object> params, Object expected) {
    return params.values().stream().anyMatch(expected::equals);
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<OrderItemPo> capturedSelectList() {
    ArgumentCaptor<LambdaQueryWrapper<OrderItemPo>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper).selectList(captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<OrderItemPo> capturedSelectCount() {
    ArgumentCaptor<LambdaQueryWrapper<OrderItemPo>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper).selectCount(captor.capture());
    return captor.getValue();
  }
}
