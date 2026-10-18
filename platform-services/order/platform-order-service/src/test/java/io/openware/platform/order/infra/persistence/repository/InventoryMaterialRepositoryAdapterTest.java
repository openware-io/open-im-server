package io.openware.platform.order.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.order.domain.inventory.model.InventoryMaterial;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository;
import io.openware.platform.order.infra.persistence.mapper.InventoryMaterialMapper;
import io.openware.platform.order.infra.persistence.po.InventoryMaterialPo;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 物料仓储适配器的 SQL 形状与列保真（真实适配器 + mock mapper）。
 *
 * <p><b>这个类为什么必须存在</b>：{@code InventoryMaterialPo} 上有两个
 * {@code @TableField(exist = false)} 字段（{@code onHandQty} / {@code reservedQty}——DDL 里没有这两列）。
 * 只要有任何一条写入路径对它们生成列引用，MyBatis-Plus 会在**运行时**抛
 * {@code can not find lambda cache for this property [...]}，而 {@code test-compile} 完全干净
 * （钱包切片的 CAS 适配器就是这样把四条资金路径全部弄挂的，只有集成测试发现得了）。本类把那个缺口补上：
 * <ul>
 *   <li>{@link #tableInfoRegistersTheTwoNonColumnFields()} 钉住「哪些字段不是列」；</li>
 *   <li>{@link #toPoNeverSetsNonColumnFields()} 钉住适配器对它们一个都不碰；</li>
 *   <li>{@link #updateWritesTheEditableColumnsExplicitlyIncludingNulls()} 钉住「清空」是结构性的：
 *       可编辑列整组显式写出，null 也进 SET（{@code updateById} 的 NOT_NULL 会整列跳过）；</li>
 *   <li>{@link #updateCarriesTheJsonTypeHandlerForImageUrls()} 钉住 json 列的处理器由适配器显式带上。</li>
 * </ul>
 *
 * <p>从 {@code InventoryQueryPagingTest} 迁移过来的**查询语法断言**（{@code LIKE}、{@code IN}、
 * {@code ORDER BY id DESC}、{@code LIMIT/OFFSET}）也落在本类：服务层只表达业务条件，
 * 查询语法是适配器的职责。
 */
class InventoryMaterialRepositoryAdapterTest {

  private static final long TENANT_ID = 1L;
  private static final long STORE_ID = 3L;
  private static final long MATERIAL_ID = 10L;

  @BeforeAll
  static void registerTableInfo() {
    MybatisConfiguration configuration = new MybatisConfiguration();
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
    assistant.setCurrentNamespace(InventoryMaterialRepositoryAdapterTest.class.getName());
    TableInfoHelper.initTableInfo(assistant, InventoryMaterialPo.class);
  }

  private final InventoryMaterialMapper mapper = mock(InventoryMaterialMapper.class);
  private final InventoryMaterialRepositoryAdapter adapter = new InventoryMaterialRepositoryAdapter(mapper);

  // ------------------------------------------------------- 列集合（非列字段）

  /** 物料的结存/预占是展示字段，不是列：DDL 里没有，MyBatis-Plus 也不为它们解析列名。 */
  @Test
  void tableInfoRegistersTheTwoNonColumnFields() {
    TableInfo info = TableInfoHelper.getTableInfo(InventoryMaterialPo.class);
    assertNotNull(info, "InventoryMaterialPo 的 TableInfo 必须已注册（SQL 形状断言的前提）");
    Set<String> columnProperties = info.getFieldList().stream()
        .map(TableFieldInfo::getProperty).collect(Collectors.toSet());
    Set<String> nonColumnProperties = declaredNonColumnProperties(InventoryMaterialPo.class);

    assertEquals(Set.of("onHandQty", "reservedQty"), nonColumnProperties,
        "非列字段集变了：写入路径的列集合必须同步复核");
    for (String property : nonColumnProperties) {
      assertFalse(columnProperties.contains(property),
          property + " 标了 @TableField(exist = false)，绝不能出现在列集合里");
    }
    // 这些必须是真列（它们是写入/筛选的依据）。
    assertTrue(columnProperties.containsAll(Set.of("purchasePrice", "currencyCode", "imageUrls",
        "mainImageUrl", "safetyStock", "createdAt")), "关键业务列必须在列集合里: " + columnProperties);
  }

  /** 适配器构造持久化对象时对两个非列字段一个都不碰（碰了就可能在 UPDATE 里生成列引用）。 */
  @Test
  void toPoNeverSetsNonColumnFields() {
    InventoryMaterialPo po = InventoryMaterialRepositoryAdapter.toPo(material());

    assertNull(po.getOnHandQty(), "onHandQty 不是列，适配器不得写入");
    assertNull(po.getReservedQty(), "reservedQty 不是列，适配器不得写入");
  }

  // ---------------------------------------------------------------- 写入

  /** 新增只写业务列，并自己补审计时间（领域模型不承载记账列）；json 列原样交给 TypeHandler。 */
  @Test
  void insertWritesBusinessColumnsAndFillsAuditTimestamps() {
    when(mapper.insert(any(InventoryMaterialPo.class))).thenReturn(1);

    InventoryMaterialRepository.Persisted<InventoryMaterial> persisted = adapter.insert(material());

    ArgumentCaptor<InventoryMaterialPo> captor = ArgumentCaptor.forClass(InventoryMaterialPo.class);
    verify(mapper).insert(captor.capture());
    InventoryMaterialPo written = captor.getValue();
    assertEquals(MATERIAL_ID, written.getId());
    assertEquals("M-10", written.getMaterialCode());
    assertEquals(List.of("/a.png", "/b.png"), written.getImageUrls());
    assertEquals("/a.png", written.getMainImageUrl());
    assertEquals(0, written.getVersion());
    assertNotNull(written.getCreatedAt(), "created_at 必须由适配器在落库时填充");
    assertNotNull(written.getUpdatedAt(), "updated_at 必须由适配器在落库时填充");
    assertNull(written.getOnHandQty());
    assertNull(written.getReservedQty());
    // 返回的聚合带上落库时间，响应视图才有 createdAt。
    assertNotNull(persisted.aggregate().createdAt());
    assertNotNull(persisted.audit().updatedAt());
  }

  /** 版本号为 null 时兜底 0（DDL 是 NOT NULL DEFAULT 0）。 */
  @Test
  void insertDefaultsVersionWhenAbsent() {
    when(mapper.insert(any(InventoryMaterialPo.class))).thenReturn(1);

    adapter.insert(new InventoryMaterial(null, TENANT_ID, STORE_ID, "M-X", "物料", "耗材", "件", null,
        BigDecimal.ZERO, null, "USD", null, null, "ACTIVE", null, null));

    ArgumentCaptor<InventoryMaterialPo> captor = ArgumentCaptor.forClass(InventoryMaterialPo.class);
    verify(mapper).insert(captor.capture());
    assertEquals(0, captor.getValue().getVersion());
  }

  /**
   * 更新把可编辑列**整组显式写出**：迁移前用 {@code updateById} 时 null 会被 NOT_NULL 策略整列跳过，
   * 「0 = 清空采购价」「清空描述」会静默失效（响应已清空、库里还在）。
   */
  @Test
  void updateWritesTheEditableColumnsExplicitlyIncludingNulls() {
    when(mapper.update(isNull(), any())).thenReturn(1);
    when(mapper.selectById(MATERIAL_ID)).thenReturn(po());

    adapter.update(new InventoryMaterial(MATERIAL_ID, TENANT_ID, STORE_ID, "M-10", "新名", "饮品", "瓶",
        null, new BigDecimal("3"), null, "USD", List.of("/new.png"), "/new.png", "ACTIVE", 0,
        LocalDateTime.of(2026, 9, 19, 20, 0, 0)), 0);

    LambdaUpdateWrapper<InventoryMaterialPo> wrapper = capturedUpdate();
    String set = wrapper.getSqlSet();
    for (String column : List.of("name", "category", "unit", "description", "safety_stock", "purchase_price",
        "currency_code", "image_urls", "main_image_url", "updated_at")) {
      assertTrue(set.contains(column), column + " 必须进 SET（整组显式写出）: " + set);
    }
    // 清空必须真的下发 NULL（不是跳过该列）。
    assertTrue(evaluatedParams(wrapper).values().contains(null),
        "清空描述/采购价必须把 NULL 作为绑定参数下发: " + set);
    // 非可编辑列不得被改写；version 只允许以 SQL 自增形式出现。
    for (String column : List.of("material_code", "tenant_id", "store_id", "status",
        "created_by", "created_at", "updated_by")) {
      assertFalse(set.contains(column), column + " 不是可编辑列，不得进 SET: " + set);
    }
    assertTrue(set.contains("version = version + 1"), "版本自增必须写在 SQL: " + set);
    assertTrue(evaluatedSql(wrapper).contains("VERSION = ?"), "必须带 version 谓词: " + evaluatedSql(wrapper));
    // 非列字段绝不能进 SET（本项目真实发生过的运行时炸点）。
    assertFalse(set.contains("on_hand_qty"), "onHandQty 不是列: " + set);
    assertFalse(set.contains("reserved_qty"), "reservedQty 不是列: " + set);
    assertTrue(evaluatedSql(wrapper).contains("ID = ?"), "必须按主键定位: " + evaluatedSql(wrapper));
  }

  /** json 列的 TypeHandler 必须由适配器显式带上（Wrapper 路径不会自动解析 PO 上的声明）。 */
  @Test
  void updateCarriesTheJsonTypeHandlerForImageUrls() {
    when(mapper.update(isNull(), any())).thenReturn(1);
    when(mapper.selectById(MATERIAL_ID)).thenReturn(po());

    adapter.update(material(), 0);

    String set = capturedUpdate().getSqlSet();
    assertTrue(set.contains("image_urls"), set);
    assertTrue(set.contains("typeHandler"), "image_urls 的占位符必须带 typeHandler，否则参数绑定会找不到处理器: " + set);
  }

  /**
   * 记账列（created_by/updated_by）不可改：更新后响应里的原值必须来自库里，
   * 而不是靠 {@code NOT_NULL} 策略的偶然行为（显式列 UPDATE 根本不会写到它们）。
   */
  @Test
  void updateReturnsTheStoredAuditValues() {
    InventoryMaterialPo stored = po();
    stored.setCreatedBy(9L);
    stored.setUpdatedBy(8L);
    when(mapper.update(isNull(), any())).thenReturn(1);
    when(mapper.selectById(MATERIAL_ID)).thenReturn(stored);

    InventoryMaterialRepository.Persisted<InventoryMaterial> persisted = adapter.update(material(), 0);

    assertEquals(9L, persisted.audit().createdBy(), "created_by 必须回库取原值");
    assertEquals(8L, persisted.audit().updatedBy(), "updated_by 必须回库取原值");
    assertEquals(stored.getCreatedAt(), persisted.audit().createdAt(), "created_at 是不可变列");
    assertNotNull(persisted.audit().updatedAt(), "updated_at 必须是本次写入时刻");
  }

  // ---------------------------------------------------------------- 读取

  /** 门店内按 id 定位：租户 + 门店 + 主键三个条件都要落到 WHERE。 */
  @Test
  void findInStoreQueriesTenantStoreAndId() {
    when(mapper.selectOne(any())).thenReturn(po());

    assertTrue(adapter.findInStore(TENANT_ID, STORE_ID, MATERIAL_ID).isPresent());

    Map<String, Object> params = evaluatedParams(capturedSelect());
    assertTrue(containsNumber(params, TENANT_ID) && containsNumber(params, STORE_ID)
            && containsNumber(params, MATERIAL_ID), "必须按 (租户, 门店, 物料) 定位: " + params.values());
    // 状态不在这里过滤：是否「存在且启用」是应用层的业务判断（读 active()）。
    assertFalse(evaluatedSql(capturedSelect()).contains("STATUS"),
        "端口不做状态过滤，由应用层判定: " + evaluatedSql(capturedSelect()));
  }

  /** id 为 null 不查库。 */
  @Test
  void findByIdShortCircuitsOnNullId() {
    assertTrue(adapter.findById(null).isEmpty());
    verify(mapper, never()).selectById(any());
  }

  /** 批量读走 selectByIds；空集合不查库。 */
  @Test
  void findByIdsDelegatesToBatchSelect() {
    when(mapper.selectByIds(any())).thenReturn(List.of(po()));

    assertEquals(1, adapter.findByIds(List.of(MATERIAL_ID)).size());
    assertTrue(adapter.findByIds(List.of()).isEmpty(), "空集合直接返回空列表");
    verify(mapper).selectByIds(any());
  }

  /**
   * 关键字命中只取主键（流水/成本按物料名检索的两段式查询），且名称/编码是 OR 关系。
   */
  @Test
  void findIdsByNameOrCodeSelectsOnlyIdsWithNameOrCodeLike() {
    when(mapper.selectList(any())).thenReturn(List.of(po()));

    assertEquals(List.of(MATERIAL_ID), adapter.findIdsByNameOrCode(TENANT_ID, STORE_ID, "可乐"));

    LambdaQueryWrapper<InventoryMaterialPo> wrapper = capturedList();
    String sql = evaluatedSql(wrapper);
    assertTrue(sql.contains("NAME LIKE"), "关键字必须能命中物料名称: " + sql);
    assertTrue(sql.contains("MATERIAL_CODE LIKE"), "关键字必须能命中物料编码: " + sql);
    assertTrue(sql.contains(" OR "), "名称与编码是 OR 关系: " + sql);
    assertEquals("id", wrapper.getSqlSelect(), "只取主键，不整行回传");
    assertTrue(containsNumber(evaluatedParams(wrapper), TENANT_ID)
            && containsNumber(evaluatedParams(wrapper), STORE_ID), "必须限定租户 + 门店");
  }

  /** 关键字为 null（未传）时不得拼出 LIKE 条件。 */
  @Test
  void findIdsByNameOrCodeSkipsTheLikeWhenKeywordAbsent() {
    when(mapper.selectList(any())).thenReturn(List.of());

    adapter.findIdsByNameOrCode(TENANT_ID, STORE_ID, null);

    assertFalse(evaluatedSql(capturedList()).contains("LIKE"), "未传关键字不得拼 LIKE");
  }

  // ---------------------------------------------------------------- 分页

  /** 分页 = 同口径 COUNT + 一页记录：id 倒序、LIMIT/OFFSET 由偏移与上限翻译而来。 */
  @Test
  void findPageCountsAndFetchesOneWindowOrderedByIdDesc() {
    when(mapper.selectCount(any())).thenReturn(7L);
    when(mapper.selectList(any())).thenReturn(List.of(po()));

    InventoryMaterialRepository.MaterialPage page = adapter.findPage(query(20L, 10L, null, null, "可乐"));

    assertEquals(7L, page.total());
    assertEquals(1, page.materials().size());
    String sql = evaluatedSql(capturedList());
    assertTrue(sql.contains("ORDER BY ID DESC"), "排序必须是 id desc: " + sql);
    assertTrue(sql.contains("LIMIT 10"), "上限必须落到 SQL: " + sql);
    assertTrue(sql.contains("OFFSET 20"), "偏移必须落到 SQL: " + sql);
    assertTrue(sql.contains("NAME LIKE") && sql.contains("MATERIAL_CODE LIKE"),
        "关键字过滤必须在取页与计数里同口径: " + sql);
  }

  /** 命中为空时不再查列表（与 MyBatis-Plus 分页「总数为 0 不下发 LIMIT 查询」同一效果）。 */
  @Test
  void findPageSkipsTheWindowQueryWhenNoRowMatches() {
    when(mapper.selectCount(any())).thenReturn(0L);

    assertEquals(0L, adapter.findPage(query(0L, 10L, null, null, null)).total());
    verify(mapper, never()).selectList(any());
  }

  /** 未传的筛选条件不得变成 SQL 条件（空白筛选已在应用层归一为 null）。 */
  @Test
  void findPageOmitsAbsentFilters() {
    when(mapper.selectCount(any())).thenReturn(1L);
    when(mapper.selectList(any())).thenReturn(List.of(po()));

    adapter.findPage(query(0L, 10L, null, null, null));

    String sql = evaluatedSql(capturedList());
    assertFalse(sql.contains("STATUS = ?"), "未传状态不得进 WHERE: " + sql);
    assertFalse(sql.contains("CATEGORY = ?"), "未传分类不得进 WHERE: " + sql);
    assertFalse(sql.contains("LIKE"), "未传关键字不得进 WHERE: " + sql);
    assertFalse(sql.contains("CREATED_AT"), "未传时间区间不得进 WHERE: " + sql);
  }

  /** 时间区间落在 {@code created_at} 列上（不用函数包裹，保持索引可用）。 */
  @Test
  void findPageAppliesCreatedAtRangeOnTheColumn() {
    when(mapper.selectCount(any())).thenReturn(1L);
    when(mapper.selectList(any())).thenReturn(List.of(po()));
    LocalDateTime from = LocalDateTime.of(2026, 9, 1, 0, 0);
    LocalDateTime to = LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000);

    adapter.findPage(new InventoryMaterialRepository.MaterialQuery(TENANT_ID, STORE_ID, "ACTIVE", "耗材",
        null, from, to, 0L, 10L));

    String sql = evaluatedSql(capturedList());
    assertTrue(sql.contains("CREATED_AT >= ?") && sql.contains("CREATED_AT <= ?"),
        "时间条件必须落在 created_at 列上: " + sql);
    Map<String, Object> params = evaluatedParams(capturedList());
    assertTrue(params.containsValue(from) && params.containsValue(to), "区间端点必须原样下发: " + params.values());
  }

  // ------------------------------------------------------------- helpers

  private static InventoryMaterialRepository.MaterialQuery query(long offset, long limit, String status,
      String category, String nameOrCode) {
    return new InventoryMaterialRepository.MaterialQuery(TENANT_ID, STORE_ID, status, category, nameOrCode,
        null, null, offset, limit);
  }

  private static InventoryMaterial material() {
    return new InventoryMaterial(MATERIAL_ID, TENANT_ID, STORE_ID, "M-10", "可乐", "饮品", "瓶", "描述",
        new BigDecimal("2"), new BigDecimal("350"), "USD", List.of("/a.png", "/b.png"), "/a.png", "ACTIVE", 0,
        LocalDateTime.of(2026, 9, 19, 20, 0, 0));
  }

  private static InventoryMaterialPo po() {
    InventoryMaterialPo po = new InventoryMaterialPo();
    po.setId(MATERIAL_ID);
    po.setTenantId(TENANT_ID);
    po.setStoreId(STORE_ID);
    po.setMaterialCode("M-10");
    po.setName("可乐");
    po.setCategory("饮品");
    po.setUnit("瓶");
    po.setSafetyStock(new BigDecimal("2"));
    po.setPurchasePrice(new BigDecimal("350"));
    po.setCurrencyCode("USD");
    po.setImageUrls(List.of("/a.png", "/b.png"));
    po.setMainImageUrl("/a.png");
    po.setStatus("ACTIVE");
    po.setVersion(0);
    po.setCreatedBy(9L);
    po.setCreatedAt(LocalDateTime.of(2026, 9, 19, 20, 0, 0));
    po.setUpdatedBy(9L);
    po.setUpdatedAt(LocalDateTime.of(2026, 9, 19, 20, 0, 0));
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

  private static boolean containsNumber(Map<String, Object> params, long expected) {
    return params.values().stream()
        .anyMatch(value -> value instanceof Number number && number.longValue() == expected);
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<InventoryMaterialPo> capturedSelect() {
    ArgumentCaptor<LambdaQueryWrapper<InventoryMaterialPo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper).selectOne(captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<InventoryMaterialPo> capturedList() {
    ArgumentCaptor<LambdaQueryWrapper<InventoryMaterialPo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper).selectList(captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private LambdaUpdateWrapper<InventoryMaterialPo> capturedUpdate() {
    ArgumentCaptor<LambdaUpdateWrapper<InventoryMaterialPo>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(mapper).update(isNull(), captor.capture());
    return captor.getValue();
  }
}
