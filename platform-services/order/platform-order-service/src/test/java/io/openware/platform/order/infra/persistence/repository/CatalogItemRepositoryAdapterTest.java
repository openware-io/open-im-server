package io.openware.platform.order.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.infra.persistence.mapper.CatalogItemMapper;
import io.openware.platform.order.infra.persistence.po.CatalogItemPo;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 目录项仓储适配器的 SQL 形状与列保真（真实适配器 + mock mapper）。
 *
 * <p><b>这个类为什么必须存在</b>：{@code CatalogItemPo} 上有三个
 * {@code @TableField(exist = false)} 字段（{@code available} / {@code unavailableReason} /
 * {@code availableQuantity}）。只要有任何一条写入路径对它们生成了列引用，MyBatis-Plus 会在
 * <b>运行时</b>抛 {@code can not find lambda cache for this property [...]}，
 * 而 {@code test-compile} 完全干净——钱包切片的 CAS 适配器就是这样把 recharge/consume/refund/release
 * 全部弄挂的，只有集成测试发现得了。本类把那个缺口补上：
 * <ul>
 *   <li>{@link #tableInfoRegistersTheThreeNonColumnFields()} 钉住「哪些字段不是列」，
 *       字段集变化必须显式反映到这条断言上；</li>
 *   <li>{@link #toPoNeverSetsNonColumnFields()} 钉住适配器对它们一个都不碰；</li>
 *   <li>{@link #updateByIdUsesThePoTableInfo()} 断言写入走的是**实体方法**，
 *       因此 {@code image_urls} 的 JacksonTypeHandler 由 TableInfo 解析（Wrapper 路径需要调用方
 *       自己带上 TypeHandler，那会把 MyBatis 类型拉进应用层）。</li>
 * </ul>
 */
class CatalogItemRepositoryAdapterTest {

  private static final long ITEM_ID = 700L;

  /**
   * 注册 {@code ord_catalog_item} 的 {@code TableInfo}。
   *
   * <p>没有这一步，lambda 的列名解析在单元测试里是<b>空操作</b>（{@code eq}/{@code set} 只登记方法引用），
   * 表现为「断言莫名其妙地空」而不是报错。
   */
  @BeforeAll
  static void registerTableInfo() {
    MybatisConfiguration configuration = new MybatisConfiguration();
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
    assistant.setCurrentNamespace(CatalogItemRepositoryAdapterTest.class.getName());
    TableInfoHelper.initTableInfo(assistant, CatalogItemPo.class);
  }

  private final CatalogItemMapper mapper = mock(CatalogItemMapper.class);
  private final CatalogItemRepositoryAdapter adapter = new CatalogItemRepositoryAdapter(mapper);

  // ------------------------------------------------------- 列集合（非列字段）

  /** 目录项的三个展示字段不是列：DDL 里没有，MyBatis-Plus 也不为它们解析列名。 */
  @Test
  void tableInfoRegistersTheThreeNonColumnFields() throws Exception {
    TableInfo info = TableInfoHelper.getTableInfo(CatalogItemPo.class);
    assertNotNull(info, "CatalogItemPo 的 TableInfo 必须已注册（SQL 形状断言的前提）");
    Set<String> columnProperties = info.getFieldList().stream()
        .map(TableFieldInfo::getProperty).collect(Collectors.toSet());
    Set<String> nonColumnProperties = declaredNonColumnProperties(CatalogItemPo.class);

    assertEquals(Set.of("available", "unavailableReason", "availableQuantity"), nonColumnProperties,
        "非列字段集变了：写入路径的列集合必须同步复核");
    for (String property : nonColumnProperties) {
      assertFalse(columnProperties.contains(property),
          property + " 标了 @TableField(exist = false)，绝不能出现在列集合里");
    }
    // 三个必须是真列（它们是点单列表的排序/过滤依据）。
    assertTrue(columnProperties.contains("sortOrder"), "sort_order 是列");
    assertTrue(columnProperties.contains("imageUrls"), "image_urls 是列（JSON）");
  }

  /** 适配器构造持久化对象时对三个非列字段一个都不碰（碰了就可能在 UPDATE 里生成列引用）。 */
  @Test
  void toPoNeverSetsNonColumnFields() {
    CatalogItemPo po = CatalogItemRepositoryAdapter.toPo(item());

    assertEquals(null, po.getAvailable(), "available 不是列，适配器不得写入");
    assertEquals(null, po.getUnavailableReason(), "unavailableReason 不是列，适配器不得写入");
    assertEquals(null, po.getAvailableQuantity(), "availableQuantity 不是列，适配器不得写入");
  }

  // ---------------------------------------------------------------- 写入

  /**
   * 写入走实体方法（{@code insert} / {@code updateById}），由 TableInfo 解析 typeHandler：
   * {@code image_urls} 的 {@code JacksonTypeHandler} 因此不需要调用方显式携带。
   */
  @Test
  void updateByIdUsesThePoTableInfo() {
    when(mapper.selectById(ITEM_ID)).thenReturn(po(ITEM_ID));
    when(mapper.updateById(any(CatalogItemPo.class))).thenReturn(1);

    adapter.update(item());

    ArgumentCaptor<CatalogItemPo> captor = ArgumentCaptor.forClass(CatalogItemPo.class);
    verify(mapper).updateById(captor.capture());
    CatalogItemPo written = captor.getValue();
    assertEquals(ITEM_ID, written.getId());
    // JSON 列由领域模型原样带出，序列化交给 PO 上声明的 JacksonTypeHandler。
    assertEquals(List.of("/a.png", "/b.png"), written.getImageUrls());
    // 不可变审计列从库里取回原值（不依赖 NOT_NULL 策略的偶然行为）。
    assertEquals(po(ITEM_ID).getCreatedAt(), written.getCreatedAt());
    assertEquals(po(ITEM_ID).getCreatedBy(), written.getCreatedBy());
    // 非列字段在写出的实体上依然是 null。
    assertEquals(null, written.getAvailable());
    assertEquals(null, written.getAvailableQuantity());
  }

  /** 新增时审计时间由适配器兜底（领域模型不承载审计列）。 */
  @Test
  void insertFillsAuditTimestampsInTheAdapter() {
    adapter.insert(item());

    ArgumentCaptor<CatalogItemPo> captor = ArgumentCaptor.forClass(CatalogItemPo.class);
    verify(mapper).insert(captor.capture());
    CatalogItemPo written = captor.getValue();
    assertNotNull(written.getCreatedAt(), "created_at 必须由适配器在落库时填充");
    assertNotNull(written.getUpdatedAt(), "updated_at 必须由适配器在落库时填充");
    assertEquals(List.of("/a.png", "/b.png"), written.getImageUrls());
  }

  // ---------------------------------------------------------------- 读取

  /** 按 id 取单条；id 为 null 不查库（视图里的占位聚合不能当成一次查询）。 */
  @Test
  void findByIdShortCircuitsOnNullId() {
    assertTrue(adapter.findById(null).isEmpty());
  }

  /** 点单列表口径：ACTIVE + 门店（或租户级通用）+ 按 sort_order, id 升序；分类为可选条件。 */
  @Test
  void findOrderableAppliesStatusStoreScopeAndOrder() {
    when(mapper.selectList(any())).thenReturn(List.of(po(ITEM_ID)));

    List<CatalogItem> items = adapter.find(CatalogItemRepository.CatalogItemQuery.orderable(1L, 3L, "酒水"));

    assertEquals(1, items.size());
    assertEquals(ITEM_ID, items.getFirst().id());
    LambdaQueryWrapper<CatalogItemPo> wrapper = capturedSelect();
    String sql = evaluatedSql(wrapper);
    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(sql.contains("STATUS = ?"), "点单列表只看 ACTIVE: " + sql);
    assertTrue(params.containsValue("ACTIVE"), "状态条件必须是 ACTIVE: " + params.values());
    assertTrue(params.containsValue("酒水"), "分类条件必须落到 SQL: " + params.values());
    assertTrue(sql.contains("STORE_ID = ?") && sql.contains("STORE_ID IS NULL"),
        "门店口径 = 本门店 或 租户级通用: " + sql);
    assertTrue(sql.contains("ORDER BY") && sql.contains("SORT_ORDER"), "按 sort_order 升序: " + sql);
  }

  /** 商品反查口径（不过滤状态）：历史 INACTIVE 目录项也必须能被复用，否则会建出第二条目录项。 */
  @Test
  void findLinkedToProductDoesNotFilterStatus() {
    when(mapper.selectList(any())).thenReturn(List.of());

    adapter.find(CatalogItemRepository.CatalogItemQuery.linkedToProduct(1L, 3L, 50L));

    Map<String, Object> params = evaluatedParams(capturedSelect());
    assertTrue(params.containsValue(50L), "必须按 product_id 定位: " + params.values());
    assertFalse(params.containsValue("ACTIVE"), "反查不得按状态过滤: " + params.values());
  }

  /** 分类改名是单条条件 UPDATE（不是逐行读改写）。 */
  @Test
  void renameCategoryIsASingleConditionalUpdate() {
    when(mapper.update(isNull(), any())).thenReturn(3);

    assertEquals(3, adapter.renameCategory(1L, 3L, "酒水", "酒水饮料"));

    LambdaUpdateWrapper<CatalogItemPo> wrapper = capturedUpdate();
    String sql = evaluatedSql(wrapper);
    assertTrue(sql.contains("CATEGORY = ?"), "必须按旧分类名定位: " + sql);
    assertTrue(wrapper.getSqlSet().contains("category"), "必须改写 category: " + wrapper.getSqlSet());
    assertTrue(wrapper.getSqlSet().contains("updated_at"), "必须刷新 updated_at: " + wrapper.getSqlSet());
    assertFalse(wrapper.getSqlSet().contains("available_quantity"),
        "非列字段不得进入 SET: " + wrapper.getSqlSet());
  }

  // ---------------------------------------------------------------- helpers

  private static CatalogItem item() {
    return new CatalogItem(ITEM_ID, 50L, 1L, 3L, "酒水", "PRODUCT", "百威啤酒", "瓶", new BigDecimal("1500"),
        "描述", List.of("/a.png", "/b.png"), "/a.png", "ACTIVE", false, 1);
  }

  private static CatalogItemPo po(Long id) {
    CatalogItemPo po = new CatalogItemPo();
    po.setId(id);
    po.setProductId(50L);
    po.setTenantId(1L);
    po.setStoreId(3L);
    po.setCategory("酒水");
    po.setItemType("PRODUCT");
    po.setName("百威啤酒");
    po.setUnit("瓶");
    po.setUnitPrice(new BigDecimal("1500"));
    po.setImageUrls(List.of("/a.png", "/b.png"));
    po.setMainImageUrl("/a.png");
    po.setStatus("ACTIVE");
    po.setStockControlled(false);
    po.setSortOrder(1);
    po.setCreatedBy(9L);
    po.setCreatedAt(java.time.LocalDateTime.of(2026, 9, 19, 20, 0, 0));
    po.setUpdatedBy(9L);
    po.setUpdatedAt(java.time.LocalDateTime.of(2026, 9, 19, 20, 0, 0));
    return po;
  }

  /** PO 上标了 {@code @TableField(exist = false)} 的字段名（反射读注解，不猜）。 */
  private static Set<String> declaredNonColumnProperties(Class<?> type) {
    Set<String> result = new java.util.LinkedHashSet<>();
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

  /** 求值后的参数表（先触发 SQL 求值，再读参数——{@code eq} 的值是惰性进参数表的）。 */
  private static Map<String, Object> evaluatedParams(
      com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper) {
    evaluatedSql(wrapper);
    return wrapper.getParamNameValuePairs();
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<CatalogItemPo> capturedSelect() {
    ArgumentCaptor<LambdaQueryWrapper<CatalogItemPo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper).selectList(captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private LambdaUpdateWrapper<CatalogItemPo> capturedUpdate() {
    ArgumentCaptor<LambdaUpdateWrapper<CatalogItemPo>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(mapper).update(isNull(), captor.capture());
    return captor.getValue();
  }

  /** 读取单条并断言存在（与其它用例读法一致）。 */
  @Test
  void findByIdReturnsTheStoredItemWithJsonImages() {
    when(mapper.selectById(ITEM_ID)).thenReturn(po(ITEM_ID));

    Optional<CatalogItem> found = adapter.findById(ITEM_ID);

    assertTrue(found.isPresent());
    assertEquals(List.of("/a.png", "/b.png"), found.get().imagesOrEmpty());
    assertEquals("/a.png", found.get().mainImageUrl());
  }
}
