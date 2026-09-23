package io.openware.common.audit.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.common.audit.infra.persistence.mapper.IamAuditLogMapper;
import io.openware.common.audit.infra.persistence.mapper.OperatorNameMapper;
import io.openware.common.audit.infra.persistence.mapper.TenantNameMapper;
import io.openware.common.audit.infra.persistence.po.IamAuditLogPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 审计仓储层口径回归：时间范围过滤以 {@code occurred_at} 为准并回退 {@code created_at}，排序按有效发生时间。
 *
 * <p>现场缺陷是「时间筛选静默丢行」：只按 {@code occurred_at} 过滤会丢掉历史 NULL 行，只按
 * {@code created_at} 过滤会漏掉补录/延迟上报的记录；这里直接断言生成的 SQL 片段两个分支都在。
 */
class AuditLogRepositoryImplTest {

  /** LambdaQueryWrapper 生成 SQL 需要实体元数据（与 ResourceAuditTest 同一套初始化方式）。 */
  @BeforeEach
  void initTableInfo() {
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
        IamAuditLogPo.class);
  }

  @Test
  void timeRangeFiltersByOccurredAtAndFallsBackToCreatedAtWhenNull() {
    LambdaQueryWrapper<IamAuditLogPo> wrapper = new LambdaQueryWrapper<>();

    AuditLogRepositoryImpl.applyTimeRange(wrapper, LocalDateTime.of(2026, 9, 1, 0, 0),
        LocalDateTime.of(2026, 10, 1, 0, 0));

    String sql = wrapper.getSqlSegment().toLowerCase(Locale.ROOT);
    assertTrue(sql.contains("occurred_at"), "时间过滤必须按业务发生时间: " + sql);
    assertTrue(sql.contains("occurred_at is null"), "occurred_at 为空时必须回退 created_at: " + sql);
    assertTrue(sql.contains("created_at"), "回退分支必须落到 created_at: " + sql);
  }

  @Test
  void timeRangeIsSkippedWhenBothBoundsAreAbsent() {
    LambdaQueryWrapper<IamAuditLogPo> wrapper = new LambdaQueryWrapper<>();

    AuditLogRepositoryImpl.applyTimeRange(wrapper, null, null);

    assertFalse(wrapper.getSqlSegment().toLowerCase(Locale.ROOT).contains("occurred_at"),
        "没有时间参数时不应产生时间条件");
  }

  /** 排序按「有效发生时间」：occurred_at 为空的历史行按 created_at 参与排序，而不是被排到最前/最后。 */
  @Test
  void orderByUsesEffectiveOccurredTime() {
    String descending = AuditLogRepositoryImpl.orderByClause(false);
    String ascending = AuditLogRepositoryImpl.orderByClause(true);

    assertEquals("ORDER BY COALESCE(occurred_at, created_at) DESC, id DESC", descending);
    assertEquals("ORDER BY COALESCE(occurred_at, created_at) ASC, id ASC", ascending);
  }

  /**
   * 账号表不可用（跨域只读缺授权、表不存在、实例抖动）时，姓名补全退化为「空」而不是抛异常：
   * 姓名只影响展示，不能让审计列表整页失败（与租户名称补全同一口径）。
   */
  @Test
  void operatorDisplaysDegradeToEmptyWhenAccountTableUnavailable() {
    OperatorNameMapper unavailable = mock(OperatorNameMapper.class);
    when(unavailable.selectByOperatorIds(anyList())).thenThrow(new RuntimeException("table not found"));
    AuditLogRepositoryImpl repository = new AuditLogRepositoryImpl(mock(IamAuditLogMapper.class),
        mock(TenantNameMapper.class), unavailable);

    assertTrue(repository.operatorDisplays(List.of(77L)).isEmpty(), "补全失败按空值返回，不抛出");
    assertTrue(repository.operatorDisplays(List.of()).isEmpty());
    assertTrue(repository.operatorDisplays(List.of(0L)).isEmpty(),
        "operator_id=0（上报方没带操作人）不该白查一次账号表");
    // 只有 77L 那一次真正查了账号表：空集合与 0 号操作人在进查询前就被挡掉。
    verify(unavailable, times(1)).selectByOperatorIds(anyList());
  }
}
