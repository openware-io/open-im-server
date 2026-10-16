package io.openware.platform.order.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.order.domain.catalog.model.Product;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.infra.persistence.mapper.ProductMapper;
import io.openware.platform.order.infra.persistence.po.ProductPo;
import java.lang.reflect.Field;
import java.math.BigDecimal;
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
 * 商品仓储适配器的 SQL 形状与列保真（真实适配器 + mock mapper）。
 *
 * <p>{@code ProductPo} 上有一个 {@code @TableField(exist = false)} 字段
 * （{@code serverResourceName}，「服务人员名称」展示回填）。对它生成列引用会在<b>运行时</b>抛
 * {@code can not find lambda cache for this property [...]}，编译期看不出来。本类钉住：
 * 它不是列、适配器不碰它；同时断言写入走实体方法（{@code image_urls} 的 JSON 序列化因此
 * 由 TableInfo 上的 {@code JacksonTypeHandler} 解析，调用方不需要携带 TypeHandler）。
 */
class ProductRepositoryAdapterTest {

  private static final long PRODUCT_ID = 500L;

  @BeforeAll
  static void registerTableInfo() {
    MybatisConfiguration configuration = new MybatisConfiguration();
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
    assistant.setCurrentNamespace(ProductRepositoryAdapterTest.class.getName());
    TableInfoHelper.initTableInfo(assistant, ProductPo.class);
  }

  private final ProductMapper mapper = mock(ProductMapper.class);
  private final ProductRepositoryAdapter adapter = new ProductRepositoryAdapter(mapper);

  /** 非列字段集：只有 serverResourceName；确认它不在列集合里。 */
  @Test
  void tableInfoRegistersServerResourceNameAsNonColumn() throws Exception {
    TableInfo info = TableInfoHelper.getTableInfo(ProductPo.class);
    assertNotNull(info, "ProductPo 的 TableInfo 必须已注册（SQL 形状断言的前提）");
    Set<String> columnProperties = info.getFieldList().stream()
        .map(TableFieldInfo::getProperty).collect(Collectors.toSet());
    Set<String> nonColumnProperties = new LinkedHashSet<>();
    for (Field field : ProductPo.class.getDeclaredFields()) {
      TableField annotation = field.getAnnotation(TableField.class);
      if (annotation != null && !annotation.exist()) {
        nonColumnProperties.add(field.getName());
      }
    }

    assertEquals(Set.of("serverResourceName"), nonColumnProperties,
        "非列字段集变了：写入路径的列集合必须同步复核");
    assertFalse(columnProperties.contains("serverResourceName"),
        "serverResourceName 标了 @TableField(exist = false)，绝不能出现在列集合里");
    assertTrue(columnProperties.contains("imageUrls"), "image_urls 是列（JSON）");
    assertTrue(columnProperties.contains("serverResourceId"), "server_resource_id 是列");
  }

  /** 适配器构造 PO 时不设展示字段（碰了就可能在 UPDATE 里生成列引用）。 */
  @Test
  void toPoNeverSetsTheDisplayOnlyField() {
    ProductPo po = ProductRepositoryAdapter.toPo(product());

    assertEquals(null, po.getServerResourceName(), "serverResourceName 不是列，适配器不得写入");
    assertEquals(List.of("/p-a.png", "/p-b.png"), po.getImageUrls(), "JSON 列由领域模型原样带出");
  }

  /** 新增时审计时间与 version 由适配器兜底。 */
  @Test
  void insertFillsVersionAndAuditTimestamps() {
    adapter.insert(product());

    ArgumentCaptor<ProductPo> captor = ArgumentCaptor.forClass(ProductPo.class);
    verify(mapper).insert(captor.capture());
    ProductPo written = captor.getValue();
    assertEquals(0, written.getVersion(), "version 必须由适配器兜底为 0");
    assertNotNull(written.getCreatedAt(), "created_at 必须由适配器在落库时填充");
    assertNotNull(written.getUpdatedAt(), "updated_at 必须由适配器在落库时填充");
    assertEquals(null, written.getServerResourceName());
  }

  /** 更新走实体方法，并保回不可变审计列。 */
  @Test
  void updateByIdKeepsImmutableAuditColumns() {
    when(mapper.selectById(PRODUCT_ID)).thenReturn(po(PRODUCT_ID));
    when(mapper.updateById(any(ProductPo.class))).thenReturn(1);

    adapter.update(product());

    ArgumentCaptor<ProductPo> captor = ArgumentCaptor.forClass(ProductPo.class);
    verify(mapper).updateById(captor.capture());
    ProductPo written = captor.getValue();
    assertEquals(po(PRODUCT_ID).getCreatedAt(), written.getCreatedAt());
    assertEquals(po(PRODUCT_ID).getCreatedBy(), written.getCreatedBy());
    assertNotNull(written.getUpdatedAt());
  }

  /** 商品列表口径：门店 + 状态 + 创建时间闭区间，按 sort_order, id 升序。 */
  @Test
  void findInStoreAppliesFiltersAndOrder() {
    when(mapper.selectList(any())).thenReturn(List.of(po(PRODUCT_ID)));

    List<Product> products = adapter.find(ProductRepository.ProductQuery.inStore(1L, 3L, "ON_SHELF",
        java.time.LocalDateTime.of(2026, 1, 1, 0, 0), java.time.LocalDateTime.of(2026, 12, 31, 0, 0)));

    assertEquals(1, products.size());
    LambdaQueryWrapper<ProductPo> wrapper = capturedSelect();
    Map<String, Object> params = evaluatedParams(wrapper);
    String sql = evaluatedSql(wrapper);
    assertTrue(params.containsValue("ON_SHELF"), "状态条件: " + params.values());
    assertTrue(params.containsValue(3L), "门店条件: " + params.values());
    assertTrue(sql.contains("CREATED_AT >= ?") && sql.contains("CREATED_AT <= ?"),
        "时间条件直接落在 created_at 列上（不用函数包裹）: " + sql);
    assertTrue(sql.contains("ORDER BY") && sql.contains("SORT_ORDER"), "按 sort_order 升序: " + sql);
  }

  /** 服务人员占用查询：按 item_type + server_resource_id 定位，排除自身，只取一行。 */
  @Test
  void findServerResourceOccupantExcludesSelfAndLimitsToOne() {
    when(mapper.selectList(any())).thenReturn(List.of());

    adapter.findServerResourceOccupant(1L, 3L, "SERVICE", 9L, 88L);

    LambdaQueryWrapper<ProductPo> wrapper = capturedSelect();
    String sql = evaluatedSql(wrapper);
    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(params.containsValue(9L), "按服务人员定位: " + params.values());
    assertTrue(sql.contains("ID <> ?"), "必须排除自身商品: " + sql);
    assertTrue(sql.contains("LIMIT 1"), "只取一行: " + sql);
  }

  /** 分类改名是单条条件 UPDATE；计数据同样落到 SQL 而不是拉回全部行。 */
  @Test
  void renameCategoryAndCountByCategoryAreSingleStatements() {
    when(mapper.update(any(), any())).thenReturn(2);
    when(mapper.selectCount(any())).thenReturn(5L);

    assertEquals(2, adapter.renameCategory(1L, 3L, "酒水", "酒水饮料"));
    assertEquals(5L, adapter.countByCategory(1L, 3L, "酒水"));

    ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ProductPo>> updateCaptor =
        ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
    verify(mapper).update(org.mockito.ArgumentMatchers.isNull(), updateCaptor.capture());
    assertTrue(updateCaptor.getValue().getSqlSet().contains("category"),
        "必须改写 category: " + updateCaptor.getValue().getSqlSet());
    assertFalse(updateCaptor.getValue().getSqlSet().contains("server_resource_name"),
        "非列字段不得进入 SET: " + updateCaptor.getValue().getSqlSet());
  }

  // ---------------------------------------------------------------- helpers

  private static Product product() {
    return new Product(PRODUCT_ID, 1L, 3L, "P-1", "百威啤酒", "酒水", "PRODUCT", "瓶", new BigDecimal("1500"),
        55L, null, true, 77L, "ON_SHELF", 0, "描述", List.of("/p-a.png", "/p-b.png"), "/p-a.png", 0);
  }

  private static ProductPo po(Long id) {
    ProductPo po = new ProductPo();
    po.setId(id);
    po.setTenantId(1L);
    po.setStoreId(3L);
    po.setProductCode("P-1");
    po.setName("百威啤酒");
    po.setCategory("酒水");
    po.setItemType("PRODUCT");
    po.setUnit("瓶");
    po.setSalePrice(new BigDecimal("1500"));
    po.setMaterialId(55L);
    po.setStockControlled(true);
    po.setCatalogItemId(77L);
    po.setStatus("ON_SHELF");
    po.setSortOrder(0);
    po.setImageUrls(List.of("/p-a.png", "/p-b.png"));
    po.setMainImageUrl("/p-a.png");
    po.setVersion(0);
    po.setCreatedBy(9L);
    po.setCreatedAt(java.time.LocalDateTime.of(2026, 9, 19, 20, 0, 0));
    po.setUpdatedBy(9L);
    po.setUpdatedAt(java.time.LocalDateTime.of(2026, 9, 19, 20, 0, 0));
    return po;
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
  private LambdaQueryWrapper<ProductPo> capturedSelect() {
    ArgumentCaptor<LambdaQueryWrapper<ProductPo>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper).selectList(captor.capture());
    return captor.getValue();
  }
}
