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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.order.domain.reservation.model.ReservationRuleConfig;
import io.openware.platform.order.domain.reservation.repository.ReservationRuleConfigRepository.Persisted;
import io.openware.platform.order.infra.persistence.mapper.OrdReservationRuleConfigMapper;
import io.openware.platform.order.infra.persistence.po.OrdReservationRuleConfigPo;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

/**
 * 预约规则仓储适配器的 SQL 形状与列保真（真实适配器 + mock mapper）。
 *
 * <p><b>为什么这个类必须存在</b>：规则的保存是「读出来判版本 → 带版本条件写回」，
 * 而 CAS 的确切列集合只能在这一层验证——应用层测试看不到 SQL。本类钉住两件事：
 * <ol>
 *   <li>覆盖写是**单条** {@code WHERE id = ? AND version = ?} 的条件 UPDATE，版本自增写在 SQL 里；</li>
 *   <li>更新集合只含三个窗口值与 {@code updated_at}：{@code business_type}/{@code store_id}/
 *       {@code status}/{@code idempotency_key} 是定位列与作用域列，原地改它们会让作用域唯一键
 *       与已生效配置错位。</li>
 * </ol>
 * {@code @BeforeAll} 里的 {@code TableInfoHelper.initTableInfo} 不可省：没有它，lambda 的列名解析
 * 是空操作，{@code getSqlSet()} 里没有列，断言会「莫名其妙地空」而不是报错。
 */
class ReservationRuleConfigRepositoryAdapterTest {

  private static final long RULE_ID = 12L;
  private static final long TENANT_ID = 7L;

  @BeforeAll
  static void registerTableInfo() {
    MybatisConfiguration configuration = new MybatisConfiguration();
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
    assistant.setCurrentNamespace(ReservationRuleConfigRepositoryAdapterTest.class.getName());
    TableInfoHelper.initTableInfo(assistant, OrdReservationRuleConfigPo.class);
  }

  private final OrdReservationRuleConfigMapper mapper = mock(OrdReservationRuleConfigMapper.class);
  private final ReservationRuleConfigRepositoryAdapter adapter =
      new ReservationRuleConfigRepositoryAdapter(mapper);

  /**
   * 覆盖写必须是带 version 条件的单条 UPDATE，且版本自增写在 SQL 里。
   *
   * <p>丢掉 {@code .eq(version, ...)} 会让这条断言失败：应用层的「读出来比对」不是并发保护。
   */
  @Test
  void updateWindowsWithVersionIsAConditionalUpdateWithSqlSideIncrement() {
    when(mapper.selectById(RULE_ID)).thenReturn(po(RULE_ID));
    when(mapper.update(ArgumentMatchers.isNull(), any())).thenReturn(1);

    Optional<Persisted<ReservationRuleConfig>> updated =
        adapter.updateWindowsWithVersion(config(RULE_ID).updatedWindows(30, 10, 20), 3);

    assertTrue(updated.isPresent());
    LambdaUpdateWrapper<OrdReservationRuleConfigPo> wrapper = capturedUpdate();
    String sql = evaluatedSql(wrapper);
    assertTrue(sql.contains("ID = ?"), "CAS 必须按 id 定位: " + sql);
    assertTrue(sql.contains("VERSION = ?"), "CAS 必须带 version 条件: " + sql);
    assertTrue(wrapper.getSqlSet().replace(" ", "").toUpperCase().contains("VERSION=VERSION+1"),
        "版本自增必须写在 SQL 里: " + wrapper.getSqlSet());
    assertEquals(4, updated.get().aggregate().version(), "返回的版本是期望版本 +1");
  }

  /** 更新集合只含三个窗口值 + 记账列：作用域列与状态列都不得被原地改写。 */
  @Test
  void updateWindowsWritesOnlyTheWindowColumns() {
    when(mapper.selectById(RULE_ID)).thenReturn(po(RULE_ID));
    when(mapper.update(ArgumentMatchers.isNull(), any())).thenReturn(1);

    adapter.updateWindowsWithVersion(config(RULE_ID).updatedWindows(30, 10, 20), 3);

    String set = capturedUpdate().getSqlSet();
    assertTrue(set.contains("advance_minutes"), set);
    assertTrue(set.contains("cancel_minutes"), set);
    assertTrue(set.contains("reschedule_minutes"), set);
    assertTrue(set.contains("updated_at"), "必须刷新 updated_at: " + set);
    assertFalse(set.contains("business_type"), "作用域列不得原地改（换作用域等于换一行规则）: " + set);
    assertFalse(set.contains("store_id"), "作用域列不得原地改: " + set);
    assertFalse(set.contains("status"), "状态列不参与窗口覆盖: " + set);
    assertFalse(set.contains("idempotency_key"), "幂等键不得被改写: " + set);
    assertFalse(set.contains("tenant_id"), "租户不可变: " + set);
    assertFalse(set.contains("created_at"), "created_at 不可变: " + set);
    Map<String, Object> params = evaluatedParams(capturedUpdate());
    assertTrue(containsNumber(params, RULE_ID), "按 id 定位: " + params.values());
    assertTrue(containsNumber(params, 3), "期望版本原样进入条件: " + params.values());
    assertFalse(containsNumber(params, 4), "不得把版本 +1 当成条件（那是写回后的值）: " + params.values());
  }

  /** 受影响 0 行 = 期间已被他人写入：返回 empty，适配器不得吞掉这个信号。 */
  @Test
  void updateWindowsReturnsEmptyWhenZeroRowsAffected() {
    when(mapper.selectById(RULE_ID)).thenReturn(po(RULE_ID));
    when(mapper.update(ArgumentMatchers.isNull(), any())).thenReturn(0);

    assertTrue(adapter.updateWindowsWithVersion(config(RULE_ID).updatedWindows(30, 10, 20), 3).isEmpty());
  }

  /** 新增：业务列 + created_at/updated_at；created_by/updated_by 不写（DDL 默认 0，与迁移前一致）。 */
  @Test
  void insertWritesBusinessColumnsAndTimestamps() {
    when(mapper.insert(any(OrdReservationRuleConfigPo.class))).thenReturn(1);

    Persisted<ReservationRuleConfig> inserted = adapter.insert(config(null));

    ArgumentCaptor<OrdReservationRuleConfigPo> captor = ArgumentCaptor.forClass(OrdReservationRuleConfigPo.class);
    verify(mapper).insert(captor.capture());
    OrdReservationRuleConfigPo written = captor.getValue();
    assertEquals(TENANT_ID, written.getTenantId());
    assertEquals("KTV", written.getBusinessType());
    assertEquals(0L, written.getStoreId());
    assertEquals(30, written.getAdvanceMinutes());
    assertEquals(10, written.getCancelMinutes());
    assertEquals(20, written.getRescheduleMinutes());
    assertEquals(0, written.getVersion());
    assertEquals(ReservationRuleConfig.STATUS_ACTIVE, written.getStatus());
    assertNotNull(written.getCreatedAt(), "created_at 由适配器在落库时填充");
    assertNotNull(written.getUpdatedAt(), "updated_at 由适配器在落库时填充");
    assertEquals(null, written.getCreatedBy(), "created_by 不写（DDL 默认 0 = 系统，与迁移前一致）");
    assertEquals(null, written.getUpdatedBy(), "updated_by 不写（同上）");
    assertEquals(30, inserted.aggregate().advanceMinutes());
  }

  /** 解析口径：租户 + 业态 + 门店 + 只取生效中状态，且只取一行。 */
  @Test
  void findActiveFiltersScopeAndStatus() {
    when(mapper.selectList(any())).thenReturn(List.of(po(RULE_ID)));

    assertTrue(adapter.findActive(TENANT_ID, "KTV", 0L).isPresent());

    LambdaQueryWrapper<OrdReservationRuleConfigPo> wrapper = capturedSelect();
    Map<String, Object> params = evaluatedParams(wrapper);
    String sql = evaluatedSql(wrapper);
    assertTrue(containsNumber(params, TENANT_ID), String.valueOf(params.values()));
    assertTrue(containsParam(params, "KTV"), String.valueOf(params.values()));
    assertTrue(containsNumber(params, 0), String.valueOf(params.values()));
    assertTrue(containsParam(params, ReservationRuleConfig.STATUS_ACTIVE), String.valueOf(params.values()));
    assertTrue(sql.contains("LIMIT 1"), "只取一行: " + sql);
  }

  /** 幂等键为空即视为「不存在」，不查库（避免空串匹配到别人的行）。 */
  @Test
  void findByIdempotencyKeyShortCircuitsOnBlankKey() {
    assertTrue(adapter.findByIdempotencyKey(TENANT_ID, null).isEmpty());
    assertTrue(adapter.findByIdempotencyKey(TENANT_ID, "  ").isEmpty());
  }

  // ---------------------------------------------------------------- helpers

  private static ReservationRuleConfig config(Long id) {
    return new ReservationRuleConfig(id, TENANT_ID, "KTV", 0L, 30, 10, 20, id == null ? 0 : 3,
        id == null ? "idem-1" : null, ReservationRuleConfig.STATUS_ACTIVE);
  }

  private static OrdReservationRuleConfigPo po(Long id) {
    OrdReservationRuleConfigPo po = new OrdReservationRuleConfigPo();
    po.setId(id);
    po.setTenantId(TENANT_ID);
    po.setBusinessType("KTV");
    po.setStoreId(0L);
    po.setAdvanceMinutes(30);
    po.setCancelMinutes(10);
    po.setRescheduleMinutes(20);
    po.setVersion(3);
    po.setStatus(ReservationRuleConfig.STATUS_ACTIVE);
    po.setCreatedAt(LocalDateTime.of(2026, 9, 18, 9, 0));
    po.setUpdatedAt(LocalDateTime.of(2026, 9, 18, 9, 0));
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
  private LambdaUpdateWrapper<OrdReservationRuleConfigPo> capturedUpdate() {
    ArgumentCaptor<LambdaUpdateWrapper<OrdReservationRuleConfigPo>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(mapper).update(ArgumentMatchers.isNull(), captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<OrdReservationRuleConfigPo> capturedSelect() {
    ArgumentCaptor<LambdaQueryWrapper<OrdReservationRuleConfigPo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(mapper).selectList(captor.capture());
    return captor.getValue();
  }
}
