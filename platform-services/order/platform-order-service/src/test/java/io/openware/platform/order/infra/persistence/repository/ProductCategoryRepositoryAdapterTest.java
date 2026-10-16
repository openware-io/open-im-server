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
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.order.domain.catalog.model.ProductCategory;
import io.openware.platform.order.infra.persistence.mapper.ProductCategoryMapper;
import io.openware.platform.order.infra.persistence.po.ProductCategoryPo;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 分类仓储适配器的列保真与 SQL 形状（真实适配器 + mock mapper）。
 *
 * <p>{@code ProductCategoryPo} 与 DDL 的 {@code ord_product_category} 逐列一一对应、
 * **没有** {@code @TableField(exist = false)} 字段；这条断言本身就是回归守卫：
 * 将来给 PO 加展示字段时，它会失败，从而提醒写入路径复核列集合。
 * 审计列由适配器负责（领域模型不承载它们），因此插入必须填 {@code created_at/updated_at}。
 */
class ProductCategoryRepositoryAdapterTest {

  private static final long CATEGORY_ID = 900L;

  @BeforeAll
  static void registerTableInfo() {
    MybatisConfiguration configuration = new MybatisConfiguration();
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
    assistant.setCurrentNamespace(ProductCategoryRepositoryAdapterTest.class.getName());
    TableInfoHelper.initTableInfo(assistant, ProductCategoryPo.class);
  }

  private final ProductCategoryMapper mapper = mock(ProductCategoryMapper.class);
  private final ProductCategoryRepositoryAdapter adapter = new ProductCategoryRepositoryAdapter(mapper);

  /** 分类 PO 全是真列：没有 {@code @TableField(exist = false)} 字段。 */
  @Test
  void poHasNoNonColumnFields() throws Exception {
    TableInfo info = TableInfoHelper.getTableInfo(ProductCategoryPo.class);
    assertNotNull(info, "ProductCategoryPo 的 TableInfo 必须已注册");
    Set<String> nonColumnProperties = new LinkedHashSet<>();
    for (Field field : ProductCategoryPo.class.getDeclaredFields()) {
      TableField annotation = field.getAnnotation(TableField.class);
      if (annotation != null && !annotation.exist()) {
        nonColumnProperties.add(field.getName());
      }
    }
    assertEquals(Set.of(), nonColumnProperties,
        "分类表没有非列字段；字段集变化时写入路径的列集合必须同步复核");
  }

  /** 新增：审计时间由适配器填充（领域模型只有业务事实）。 */
  @Test
  void insertFillsAuditTimestamps() {
    adapter.insert(new ProductCategory(null, 1L, 3L, "酒水", 1, "ACTIVE"));

    ArgumentCaptor<ProductCategoryPo> captor = ArgumentCaptor.forClass(ProductCategoryPo.class);
    verify(mapper).insert(captor.capture());
    ProductCategoryPo written = captor.getValue();
    assertEquals("酒水", written.getName());
    assertNotNull(written.getCreatedAt(), "created_at 必须由适配器在落库时填充");
    assertNotNull(written.getUpdatedAt(), "updated_at 必须由适配器在落库时填充");
  }

  /** 更新：刷新 updated_at，保留不可变审计列。 */
  @Test
  void updateRefreshesUpdatedAtAndKeepsCreatedAt() {
    when(mapper.selectById(CATEGORY_ID)).thenReturn(po(CATEGORY_ID));
    when(mapper.updateById(any(ProductCategoryPo.class))).thenReturn(1);

    adapter.update(new ProductCategory(CATEGORY_ID, 1L, 3L, "酒水饮料", 2, "DISABLED"));

    ArgumentCaptor<ProductCategoryPo> captor = ArgumentCaptor.forClass(ProductCategoryPo.class);
    verify(mapper).updateById(captor.capture());
    ProductCategoryPo written = captor.getValue();
    assertEquals(po(CATEGORY_ID).getCreatedAt(), written.getCreatedAt());
    assertEquals(po(CATEGORY_ID).getCreatedBy(), written.getCreatedBy());
    assertNotNull(written.getUpdatedAt());
  }

  /** 重名口径：门店 + 名称精确匹配，只取一行。 */
  @Test
  void findByNameFiltersStoreAndNameThenLimitsToOne() {
    when(mapper.selectList(any())).thenReturn(List.of(po(CATEGORY_ID)));

    assertTrue(adapter.findByName(1L, 3L, "酒水").isPresent());

    LambdaQueryWrapper<ProductCategoryPo> wrapper = capturedSelect();
    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(params.containsValue("酒水"), "按名称定位: " + params.values());
    assertTrue(params.containsValue(3L), "按门店定位: " + params.values());
    assertTrue(evaluatedSql(wrapper).contains("LIMIT 1"), "同门店同名只取一行");
  }

  /** 列表：门店内按 sort_order, name, id 升序；状态为可选条件。 */
  @Test
  void findOrdersBySortOrderNameThenId() {
    when(mapper.selectList(any())).thenReturn(List.of());

    adapter.find(1L, 3L, "ACTIVE");

    LambdaQueryWrapper<ProductCategoryPo> wrapper = capturedSelect();
    String sql = evaluatedSql(wrapper);
    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(params.containsValue("ACTIVE"), "状态条件: " + params.values());
    assertTrue(sql.contains("SORT_ORDER") && sql.contains("NAME") && sql.contains("ID"),
        "排序必须稳定（sort_order, name, id）: " + sql);
    assertFalse(sql.contains("DESC"), "升序（越小越靠前）: " + sql);
  }

  // ---------------------------------------------------------------- helpers

  private static ProductCategoryPo po(Long id) {
    ProductCategoryPo po = new ProductCategoryPo();
    po.setId(id);
    po.setTenantId(1L);
    po.setStoreId(3L);
    po.setName("酒水");
    po.setSortOrder(1);
    po.setStatus("ACTIVE");
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
  private LambdaQueryWrapper<ProductCategoryPo> capturedSelect() {
    ArgumentCaptor<LambdaQueryWrapper<ProductCategoryPo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper).selectList(captor.capture());
    return captor.getValue();
  }
}
