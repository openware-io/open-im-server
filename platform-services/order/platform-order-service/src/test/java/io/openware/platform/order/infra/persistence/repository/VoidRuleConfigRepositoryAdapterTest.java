package io.openware.platform.order.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.order.domain.order.model.VoidRuleConfig;
import io.openware.platform.order.infra.persistence.mapper.OrdVoidRuleConfigMapper;
import io.openware.platform.order.infra.persistence.po.OrdVoidRuleConfigPo;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 作废规则配置适配器的 CAS 写入（真实适配器 + mock mapper）。
 *
 * <p><b>为什么这个类必须存在</b>：{@code updateRequireApprovalWithVersion} 是"是否要求审批"的唯一写入点，
 * 它的 {@code WHERE id = ? AND version = ?} 是**唯一并发判定点**——删掉 version 条件后，
 * 两个并发改配置都会成功，后写者覆盖前者且双方都收到成功；{@code test-compile} 依然干净，
 * 只有本类能拦住。
 *
 * <p>同时钉住列集合：{@code tenant_id} / {@code business_type} / {@code store_id} / {@code status} /
 * {@code idempotency_key} 是**定位列与作用域列，不得进入 SET**——改作用域等于换一行规则，
 * 必须新插而不是原地改。把它们写进 SET 会让一条规则在不知不觉中改变归属。
 *
 * <p>断言方式：捕获传给 mapper 的 Wrapper，用 MyBatis-Plus 自己的 {@code getTargetSql()} /
 * {@code getSqlSet()} 求值，不手写 SQL 比对。{@code @BeforeAll} 里的
 * {@code TableInfoHelper.initTableInfo} 不可省：没有它 lambda 列名解析是空操作，断言会"莫名其妙地空"。
 */
class VoidRuleConfigRepositoryAdapterTest {

  private static final long CONFIG_ID = 55L;

  @BeforeAll
  static void registerTableInfo() {
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
        OrdVoidRuleConfigPo.class);
  }

  private final OrdVoidRuleConfigMapper mapper = mock(OrdVoidRuleConfigMapper.class);
  private final VoidRuleConfigRepositoryAdapter adapter = new VoidRuleConfigRepositoryAdapter(mapper);

  /**
   * CAS 形状：{@code WHERE id = ? AND version = ?}，SET 只含 {@code require_approval} 与 {@code updated_at}，
   * 版本自增写在 SQL 里。
   */
  @Test
  void updateIsConditionalOnVersionWithSqlSideIncrement() {
    when(mapper.selectById(CONFIG_ID)).thenReturn(po(CONFIG_ID));
    when(mapper.update(any(), any())).thenReturn(1);

    assertTrue(adapter.updateRequireApprovalWithVersion(config(CONFIG_ID, true), 4).isPresent());

    LambdaUpdateWrapper<OrdVoidRuleConfigPo> wrapper = capturedUpdate();
    String where = evaluatedSql(wrapper);
    assertTrue(where.contains("ID = ?"), "必须按主键定位: " + where);
    assertTrue(where.contains("VERSION = ?"), "必须有 version 条件，否则并发改配置都能成功: " + where);

    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(containsNumber(params, CONFIG_ID), "按配置 id 定位: " + params.values());
    assertTrue(containsNumber(params, 4), "期望版本必须原样进入条件: " + params.values());
    assertFalse(containsNumber(params, 5),
        "不得把版本 +1 当成条件（那是写回后的值）: " + params.values());

    String set = String.valueOf(wrapper.getSqlSet()).replace(" ", "").toLowerCase();
    assertTrue(set.contains("require_approval"), "必须写 require_approval: " + wrapper.getSqlSet());
    assertTrue(set.contains("updated_at"), "必须刷新 updated_at: " + wrapper.getSqlSet());
    assertFalse(set.contains("version=#{"),
        "version 不得作为普通 SET 列写入（应走 setSql 自增）: " + wrapper.getSqlSet());
    assertTrue(set.contains("version=version+1"),
        "版本自增必须写在 SQL 里，避免把读出来的旧版本写回: " + wrapper.getSqlSet());
  }

  /**
   * <b>列保真</b>：定位列与作用域列不得进入 SET。
   *
   * <p>把它们写进去等于让一次"改是否要求审批"顺带改了规则归属——作用域变了却仍是同一行，
   * 唯一键与实际语义就此不一致。
   */
  @Test
  void updateNeverWritesScopeAndLocatedColumns() {
    when(mapper.selectById(CONFIG_ID)).thenReturn(po(CONFIG_ID));
    when(mapper.update(any(), any())).thenReturn(1);

    adapter.updateRequireApprovalWithVersion(config(CONFIG_ID, false), 4);

    String set = String.valueOf(capturedUpdate().getSqlSet());
    assertFalse(set.contains("tenant_id"), "不得改 tenant_id: " + set);
    assertFalse(set.contains("business_type"), "不得改 business_type: " + set);
    assertFalse(set.contains("store_id"), "不得改 store_id（改作用域须新插）: " + set);
    assertFalse(set.contains("status"), "不得改 status: " + set);
    assertFalse(set.contains("idempotency_key"), "不得改 idempotency_key: " + set);
    assertFalse(set.contains("created_at"), "不得改 created_at: " + set);
  }

  /** 受影响 0 行 = 版本冲突：必须回 {@code Optional.empty()}，由调用方报冲突而不是静默成功。 */
  @Test
  void updateReturnsEmptyOnVersionConflict() {
    when(mapper.selectById(CONFIG_ID)).thenReturn(po(CONFIG_ID));
    when(mapper.update(any(), any())).thenReturn(0);

    assertTrue(adapter.updateRequireApprovalWithVersion(config(CONFIG_ID, true), 4).isEmpty());
  }

  /** 写回响应时版本必须是"写后"的值，且不可变的 created_at 从原行带回。 */
  @Test
  void updateCarriesBackIncrementedVersionAndCreatedAt() {
    OrdVoidRuleConfigPo stored = po(CONFIG_ID);
    LocalDateTime createdAt = LocalDateTime.of(2026, 9, 1, 8, 0);
    stored.setCreatedAt(createdAt);
    when(mapper.selectById(CONFIG_ID)).thenReturn(stored);
    when(mapper.update(any(), any())).thenReturn(1);

    VoidRuleConfig written = adapter.updateRequireApprovalWithVersion(config(CONFIG_ID, true), 4)
        .orElseThrow().aggregate();

    assertEquals(5, written.version(), "写后版本必须是 expectedVersion + 1");
    assertEquals(createdAt, adapter.updateRequireApprovalWithVersion(config(CONFIG_ID, true), 4)
        .orElseThrow().audit().createdAt(), "created_at 必须从原行带回");
  }

  // -------------------------------------------------------------------- helpers

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

  private static VoidRuleConfig config(Long id, Boolean requireApproval) {
    return new VoidRuleConfig(id, 100L, "KTV", 200L, requireApproval, 4, "key-" + id,
        VoidRuleConfig.STATUS_ACTIVE);
  }

  private static OrdVoidRuleConfigPo po(Long id) {
    OrdVoidRuleConfigPo po = new OrdVoidRuleConfigPo();
    po.setId(id);
    po.setTenantId(100L);
    po.setBusinessType("KTV");
    po.setStoreId(200L);
    po.setRequireApproval(false);
    po.setVersion(4);
    po.setIdempotencyKey("key-" + id);
    po.setStatus(VoidRuleConfig.STATUS_ACTIVE);
    po.setCreatedAt(LocalDateTime.now());
    po.setUpdatedAt(LocalDateTime.now());
    return po;
  }

  @SuppressWarnings("unchecked")
  private LambdaUpdateWrapper<OrdVoidRuleConfigPo> capturedUpdate() {
    ArgumentCaptor<LambdaUpdateWrapper<OrdVoidRuleConfigPo>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(mapper).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
    LambdaUpdateWrapper<OrdVoidRuleConfigPo> wrapper = captor.getValue();
    assertNotNull(wrapper);
    return wrapper;
  }
}
