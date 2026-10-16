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
import io.openware.platform.order.domain.inventory.model.InventoryStock;
import io.openware.platform.order.infra.persistence.mapper.InventoryStockMapper;
import io.openware.platform.order.infra.persistence.po.InventoryStockPo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 库存余额仓储适配器的**并发关键 SQL** 与列保真（真实适配器 + mock mapper）。
 *
 * <p><b>这个类为什么必须存在</b>：库存子域已经有正确的并发控制，本子域迁移只许保留、不许"顺手加固成
 * 先读后判"。两条防超卖语句写在 {@code InventoryStockMapper} 的注解里（单条条件 UPDATE），
 * 适配器只做委托与类型转换，因此断言分两层：
 * <ol>
 *   <li><b>SQL 文本本身</b>（反射读注解）：扣减语句必须同时带 {@code on_hand_qty = on_hand_qty - ?}
 *       与上下界条件 {@code on_hand_qty - reserved_qty >= ?}；入库语句必须在**同一条**语句里写
 *       {@code on_hand_qty = on_hand_qty + ?} 与 {@code avg_cost = ?}；加锁读必须带 {@code FOR UPDATE}。
 *       任何把条件判定搬回应用层的改动都会让这些断言失败。</li>
 *   <li><b>适配器的转换契约</b>（mock mapper）：{@code deductAvailable} 把「受影响行数 &gt; 0」表达成
 *       boolean 且不吞掉 0 行信号；{@code increaseOnHand} 原样返回行数；{@code save} 只插入。</li>
 * </ol>
 *
 * <p>断言方式与 {@code WalletAccountRepositoryAdapterTest} / {@code CatalogItemRepositoryAdapterTest}
 * 一致：{@code TableInfoHelper.initTableInfo(...)} 之后用 {@code getTargetSql()} 求值，
 * 不手写 SQL 比对、不猜列名；数值参数按数值比较（MyBatis-Plus 按 Java 类型装箱，{@code Integer} 不等于
 * {@code Long}）。
 */
class InventoryStockRepositoryAdapterTest {

  private static final long TENANT_ID = 1L;
  private static final long STORE_ID = 3L;
  private static final long MATERIAL_ID = 10L;
  private static final long OTHER_MATERIAL_ID = 11L;

  /**
   * 注册 {@code ord_inventory_stock} 的 {@code TableInfo}。
   *
   * <p>没有这一步，lambda 的列名解析在单元测试里是**空操作**（{@code eq} 只登记方法引用），
   * 于是 {@code getTargetSql()}/{@code getParamNameValuePairs()} 都是空的，断言会「莫名其妙地空过」。
   */
  @BeforeAll
  static void registerTableInfo() {
    MybatisConfiguration configuration = new MybatisConfiguration();
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
    assistant.setCurrentNamespace(InventoryStockRepositoryAdapterTest.class.getName());
    TableInfoHelper.initTableInfo(assistant, InventoryStockPo.class);
  }

  private final InventoryStockMapper mapper = mock(InventoryStockMapper.class);
  private final InventoryStockRepositoryAdapter adapter = new InventoryStockRepositoryAdapter(mapper);

  // ------------------------------------------------- 并发关键 SQL（注解文本）

  /**
   * 扣减必须是**单条带上下界的条件更新**：{@code on_hand_qty = on_hand_qty - ?} 与
   * {@code on_hand_qty - reserved_qty >= ?} 在同一条语句里，防超卖由数据库行锁判定。
   */
  @Test
  void deductAvailableSqlKeepsTheAtomicConditionalUpdate() {
    String sql = sqlOf("deductAvailable").replaceAll("\\s+", " ").toUpperCase();

    assertTrue(sql.startsWith("UPDATE ORD_INVENTORY_STOCK"), "必须是 UPDATE，实际: " + sql);
    assertTrue(sql.replace(" ", "").contains("ON_HAND_QTY=ON_HAND_QTY-#{QUANTITY}"),
        "必须用「列 = 列 - 参数」自减，而不是把读出来的值写回: " + sql);
    assertTrue(sql.replace(" ", "").contains("ON_HAND_QTY-RESERVED_QTY>=#{QUANTITY}"),
        "必须带上下界条件（可用量 >= 扣减量），否则并发下会把库存扣成负数: " + sql);
    assertTrue(sql.replace(" ", "").contains("VERSION=VERSION+1"), "版本号自增必须写在 SQL 里: " + sql);
    assertTrue(sql.contains("TENANT_ID = #{TENANTID}") && sql.contains("STORE_ID = #{STOREID}")
            && sql.contains("MATERIAL_ID = #{MATERIALID}"),
        "定位口径必须是 (租户, 门店, 物料): " + sql);
  }

  /**
   * 入库必须在**同一条语句内**加现有量并落库新的移动加权平均成本（{@code avg_cost = ?}）：
   * 拆成两条会给并发批次留下「都基于旧平均成本重算」的丢失更新窗口。
   */
  @Test
  void increaseOnHandSqlWritesQuantityAndAverageCostInOneStatement() {
    String sql = sqlOf("increaseOnHand").replaceAll("\\s+", " ").toUpperCase();

    assertTrue(sql.startsWith("UPDATE ORD_INVENTORY_STOCK"), "必须是 UPDATE，实际: " + sql);
    assertTrue(sql.replace(" ", "").contains("ON_HAND_QTY=ON_HAND_QTY+#{QUANTITY}"),
        "必须用自增写法: " + sql);
    assertTrue(sql.replace(" ", "").contains("AVG_COST=#{AVGCOST}"),
        "新的移动加权平均成本必须在同一条语句里落库: " + sql);
    assertTrue(sql.replace(" ", "").contains("CURRENCY_CODE=#{CURRENCYCODE}"),
        "成本币种快照必须与平均成本同一条语句落库: " + sql);
    assertTrue(sql.replace(" ", "").contains("VERSION=VERSION+1"), "版本号自增必须写在 SQL 里: " + sql);
  }

  /** 行锁读必须是 {@code FOR UPDATE}（事务内持有至提交，串行化同一物料的并发变更）。 */
  @Test
  void selectForUpdateSqlTakesARowLock() {
    String sql = sqlOf("selectForUpdate").replaceAll("\\s+", " ").toUpperCase();

    assertTrue(sql.startsWith("SELECT * FROM ORD_INVENTORY_STOCK"), "必须是单行读，实际: " + sql);
    assertTrue(sql.contains("FOR UPDATE"), "必须加行锁（FOR UPDATE），否则并发入库会丢失更新: " + sql);
    assertTrue(sql.contains("TENANT_ID=#{TENANTID}") && sql.contains("STORE_ID=#{STOREID}")
            && sql.contains("MATERIAL_ID=#{MATERIALID}"),
        "定位口径必须是 (租户, 门店, 物料): " + sql);
  }

  // ------------------------------------------------- 适配器转换契约

  /** 0 行受影响必须原样表达成 false（库存不足是业务结果，不是异常）。 */
  @Test
  void deductAvailableReturnsFalseWhenNoRowAffected() {
    when(mapper.deductAvailable(any(), any(), any(), any(), any())).thenReturn(0);

    assertFalse(adapter.deductAvailable(TENANT_ID, STORE_ID, MATERIAL_ID, new BigDecimal("2"),
        LocalDateTime.now()));
  }

  /** 命中 1 行即扣减成功。 */
  @Test
  void deductAvailableReturnsTrueWhenRowAffected() {
    when(mapper.deductAvailable(any(), any(), any(), any(), any())).thenReturn(1);

    assertTrue(adapter.deductAvailable(TENANT_ID, STORE_ID, MATERIAL_ID, new BigDecimal("2"),
        LocalDateTime.now()));
  }

  /** 条件更新的参数必须原样透传（数量按数值比较，定点不做换算）。 */
  @Test
  void deductAvailablePassesTheGuardParametersThrough() {
    LocalDateTime now = LocalDateTime.of(2026, 9, 19, 20, 0, 0);
    when(mapper.deductAvailable(any(), any(), any(), any(), any())).thenReturn(1);

    adapter.deductAvailable(TENANT_ID, STORE_ID, MATERIAL_ID, new BigDecimal("2.5"), now);

    ArgumentCaptor<BigDecimal> quantity = ArgumentCaptor.forClass(BigDecimal.class);
    ArgumentCaptor<LocalDateTime> updatedAt = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(mapper).deductAvailable(eq(TENANT_ID), eq(STORE_ID), eq(MATERIAL_ID),
        quantity.capture(), updatedAt.capture());
    assertEquals(0, quantity.getValue().compareTo(new BigDecimal("2.5")),
        "扣减数量必须原样下发（定点，不做任何换算）");
    assertEquals(now, updatedAt.getValue());
  }

  /** 入库的受影响行数原样返回（0 = 目标行不存在，由应用层据此抛业务异常）。 */
  @Test
  void increaseOnHandReturnsAffectedRowsAndPassesCostThrough() {
    when(mapper.increaseOnHand(any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

    assertEquals(0, adapter.increaseOnHand(TENANT_ID, STORE_ID, MATERIAL_ID, BigDecimal.ONE,
        new BigDecimal("466.666667"), "CNY", LocalDateTime.now()));

    ArgumentCaptor<BigDecimal> avgCost = ArgumentCaptor.forClass(BigDecimal.class);
    ArgumentCaptor<String> currency = ArgumentCaptor.forClass(String.class);
    verify(mapper).increaseOnHand(eq(TENANT_ID), eq(STORE_ID), eq(MATERIAL_ID), any(),
        avgCost.capture(), currency.capture(), any());
    assertEquals(0, avgCost.getValue().compareTo(new BigDecimal("466.666667")),
        "加权后的平均成本必须原样下发（6 位定点）");
    assertEquals("CNY", currency.getValue(), "成本币种快照必须与平均成本同一条语句落库");
  }

  // ------------------------------------------------- 读路径与列保真

  /** 单条读按 (租户, 门店, 物料) 定位，并把持久化对象转成领域模型。 */
  @Test
  void findQueriesTenantStoreAndMaterial() {
    when(mapper.selectOne(any())).thenReturn(po());

    Optional<InventoryStock> found = adapter.find(TENANT_ID, STORE_ID, MATERIAL_ID);

    assertTrue(found.isPresent());
    assertEquals(MATERIAL_ID, found.get().materialId());
    assertEquals(0, found.get().availableQty().compareTo(new BigDecimal("4")),
        "可用量 = 现有量 − 预留量（5 − 1）");
    LambdaQueryWrapper<InventoryStockPo> wrapper = capturedSelectOne();
    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(containsNumber(params, TENANT_ID) && containsNumber(params, STORE_ID)
            && containsNumber(params, MATERIAL_ID),
        "必须按 (租户, 门店, 物料) 定位: " + params.values());
  }

  /** 批量读按物料 id 集合一次 IN 查询（列表页避免 N+1），空集合不查库。 */
  @Test
  void findByMaterialsQueriesTheMaterialIdSet() {
    when(mapper.selectList(any())).thenReturn(List.of(po()));

    List<InventoryStock> stocks = adapter.findByMaterials(TENANT_ID, STORE_ID, List.of(MATERIAL_ID, OTHER_MATERIAL_ID));

    assertEquals(1, stocks.size());
    String sql = evaluatedSql(capturedSelectList());
    assertTrue(sql.contains("MATERIAL_ID IN"), "必须一次 IN 查询: " + sql);
    assertTrue(adapter.findByMaterials(TENANT_ID, STORE_ID, List.of()).isEmpty(),
        "空集合返回空列表，不下发 IN ()（非法 SQL）");
  }

  /** 按门店读全部余额（成本报表口径）：一次查询，委托给专用语句。 */
  @Test
  void findByStoreDelegatesToTheStoreQuery() {
    when(mapper.selectByStore(TENANT_ID, STORE_ID)).thenReturn(List.of(po()));

    assertEquals(1, adapter.findByStore(TENANT_ID, STORE_ID).size());
    verify(mapper).selectByStore(TENANT_ID, STORE_ID);
  }

  /** 行锁读走 mapper 的专用方法（不是 selectOne）。 */
  @Test
  void findForUpdateDelegatesToTheLockingQuery() {
    when(mapper.selectForUpdate(TENANT_ID, STORE_ID, MATERIAL_ID)).thenReturn(po());

    assertTrue(adapter.findForUpdate(TENANT_ID, STORE_ID, MATERIAL_ID).isPresent());
    verify(mapper).selectForUpdate(TENANT_ID, STORE_ID, MATERIAL_ID);
  }

  /** 空结果回 {@code Optional.empty()}，由调用方决定是建行还是拒绝。 */
  @Test
  void findForUpdateReturnsEmptyWhenNoRow() {
    when(mapper.selectForUpdate(TENANT_ID, STORE_ID, MATERIAL_ID)).thenReturn(null);

    assertTrue(adapter.findForUpdate(TENANT_ID, STORE_ID, MATERIAL_ID).isEmpty());
  }

  /**
   * {@code save} 只插入（首次入库建零额行），且必须自己补 {@code created_at}/{@code updated_at}（NOT NULL）。
   *
   * <p><b>为什么没有 UPDATE 分支</b>：库存的每次变更都必须走 deductAvailable/increaseOnHand 的原子条件语句，
   * 提供「按 id 覆盖写余额行」会重新引入「先读后判再写回」的竞态；领域模型不可变、没有 setter，正是为了
   * 让这条路径无从表达。断言 {@code updateById} 一次都没被调用就是钉住这一点。
   */
  @Test
  void saveInsertsOnlyAndFillsTheAuditTimestamps() {
    adapter.save(InventoryStock.empty(TENANT_ID, STORE_ID, MATERIAL_ID));

    ArgumentCaptor<InventoryStockPo> captor = ArgumentCaptor.forClass(InventoryStockPo.class);
    verify(mapper).insert(captor.capture());
    verify(mapper, never()).updateById(any(InventoryStockPo.class));
    InventoryStockPo written = captor.getValue();
    assertEquals(TENANT_ID, written.getTenantId());
    assertEquals(MATERIAL_ID, written.getMaterialId());
    assertEquals(0, written.getOnHandQty().signum());
    assertEquals(0, written.getReservedQty().signum());
    assertEquals(0, written.getVersion());
    assertNull(written.getCurrencyCode(),
        "币种留空由 DDL 的 DEFAULT 'USD' 填充（与迁移前的 INSERT 口径一致）");
    assertNotNull(written.getCreatedAt(), "created_at 是 NOT NULL，必须由适配器填充");
    assertNotNull(written.getUpdatedAt(), "updated_at 是 NOT NULL，必须由适配器填充");
  }

  /** 库存行的列集合只有真实列（该 PO 上没有展示字段），并发/成本列必须真在列集合里。 */
  @Test
  void stockTableInfoHasOnlyRealColumns() {
    TableInfo info = TableInfoHelper.getTableInfo(InventoryStockPo.class);
    assertNotNull(info, "InventoryStockPo 的 TableInfo 必须已注册（列集合断言的前提）");
    Set<String> columns = info.getFieldList().stream()
        .map(TableFieldInfo::getProperty).collect(Collectors.toSet());

    assertEquals(Set.of(), declaredNonColumnProperties(InventoryStockPo.class),
        "库存余额没有展示字段；一旦新增，写入路径的列集合必须同步复核");
    assertTrue(columns.containsAll(Set.of("onHandQty", "reservedQty", "avgCost", "currencyCode", "version")),
        "并发/成本列必须是真列: " + columns);
  }

  /** 领域模型不承载审计列：四个记账列既不在模型上，也不在适配器里被读。 */
  @Test
  void domainModelCarriesNoAuditFields() {
    Set<String> components = Arrays.stream(InventoryStock.class.getRecordComponents())
        .map(RecordComponent::getName).collect(Collectors.toSet());

    assertEquals(Set.of("id", "tenantId", "storeId", "materialId", "onHandQty", "reservedQty", "avgCost",
        "currencyCode", "version"), components,
        "库存领域模型只承载业务列（审计四列由适配器在写入时填充、读取时丢弃）");
  }

  // ------------------------------------------------------------- helpers

  /** 读取 mapper 方法上的 {@code @Select}/{@code @Update} 注解文本（并发关键 SQL 的唯一真源）。 */
  private static String sqlOf(String methodName) {
    for (Method method : InventoryStockMapper.class.getDeclaredMethods()) {
      if (!method.getName().equals(methodName)) {
        continue;
      }
      Update update = method.getAnnotation(Update.class);
      if (update != null) {
        return String.join(" ", update.value());
      }
      Select select = method.getAnnotation(Select.class);
      if (select != null) {
        return String.join(" ", select.value());
      }
    }
    throw new AssertionError("InventoryStockMapper 上找不到 " + methodName + " 的 SQL 注解");
  }

  private static boolean containsNumber(Map<String, Object> params, long expected) {
    return params.values().stream()
        .anyMatch(value -> value instanceof Number number && number.longValue() == expected);
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

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<InventoryStockPo> capturedSelectOne() {
    ArgumentCaptor<LambdaQueryWrapper<InventoryStockPo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper).selectOne(captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<InventoryStockPo> capturedSelectList() {
    ArgumentCaptor<LambdaQueryWrapper<InventoryStockPo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper).selectList(captor.capture());
    return captor.getValue();
  }

  private static InventoryStockPo po() {
    InventoryStockPo po = new InventoryStockPo();
    po.setId(20L);
    po.setTenantId(TENANT_ID);
    po.setStoreId(STORE_ID);
    po.setMaterialId(MATERIAL_ID);
    po.setOnHandQty(new BigDecimal("5"));
    po.setReservedQty(BigDecimal.ONE);
    po.setAvgCost(new BigDecimal("300"));
    po.setCurrencyCode("CNY");
    po.setVersion(0);
    return po;
  }
}
