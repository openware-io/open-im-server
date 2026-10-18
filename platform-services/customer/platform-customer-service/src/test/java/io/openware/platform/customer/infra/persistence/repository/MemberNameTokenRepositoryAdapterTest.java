package io.openware.platform.customer.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.common.exception.ApiException;
import io.openware.platform.customer.domain.member.model.MemberNameToken;
import io.openware.platform.customer.infra.persistence.mapper.MemberNameTokenMapper;
import io.openware.platform.customer.infra.persistence.po.CstMemberNameTokenPo;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 姓名盲索引适配器的「<b>先删后插 + 失败补偿</b>」（真实适配器 + mock mapper）。
 *
 * <p>为什么这条路径值得单独钉住：回填扫描的判据是「<b>没有 token 行</b>」，
 * 而重建是「删掉旧 token 再插新的」。中途失败若只留下<b>半套索引</b>，回填会把这名客户
 * 当成"已完成"，此后按姓名再也搜不到他——一个不会报错、只会静默丢数据的洞。
 * 所以适配器必须补偿删除（退回零 token 行状态，交给下一轮回填）并把原异常抛出。
 *
 * <p>租户口径也是刻意的：重建路径由服务在逐条写入前设好租户上下文，适配器不忽略租户拦截器；
 * 存量扫描（{@code findMemberIdsMissingTokens}）与跨租户枚举（{@code listTenantIdsWithMembers}）
 * 才忽略拦截器并显式传 tenantId（在 Mapper 上标注，本用例不重复断言）。
 */
class MemberNameTokenRepositoryAdapterTest {

  private static final long MEMBER_ID = 11L;
  private static final long TENANT_ID = 100L;

  /** 注册 {@code TableInfo}：不注册时 lambda 列名解析是空操作（断言会莫名其妙地空）。 */
  @BeforeAll
  static void registerTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    assistant.setCurrentNamespace(MemberNameTokenRepositoryAdapterTest.class.getName());
    TableInfoHelper.initTableInfo(assistant, CstMemberNameTokenPo.class);
  }

  private final MemberNameTokenMapper nameTokenMapper = mock(MemberNameTokenMapper.class);
  private final MemberNameTokenRepositoryAdapter adapter =
      new MemberNameTokenRepositoryAdapter(nameTokenMapper);

  /** 重建：先按 member_id 删旧的，再逐个插入新 token，且 tenant_id / member_id 由调用方给定。 */
  @Test
  void replaceForMemberDeletesOldRowsThenInsertsNewOnes() {
    adapter.replaceForMember(MEMBER_ID, List.of(
        MemberNameToken.of(TENANT_ID, MEMBER_ID, "token-a"),
        MemberNameToken.of(TENANT_ID, MEMBER_ID, "token-b")), LocalDateTime.now());

    // 删除条件必须按 member_id 定位（不是无条件删，也不能漏掉 tenant 拦截器）
    ArgumentCaptor<LambdaQueryWrapper<CstMemberNameTokenPo>> deleteCaptor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(nameTokenMapper).delete(deleteCaptor.capture());
    String deleteSql = evaluatedSql(deleteCaptor.getValue());
    assertTrue(deleteSql.contains("MEMBER_ID ="), deleteSql);
    assertTrue(containsNumber(evaluatedParams(deleteCaptor.getValue()), MEMBER_ID),
        "删除必须按档案 id 定位");

    ArgumentCaptor<CstMemberNameTokenPo> insertCaptor =
        ArgumentCaptor.forClass(CstMemberNameTokenPo.class);
    verify(nameTokenMapper, times(2)).insert(insertCaptor.capture());
    List<CstMemberNameTokenPo> rows = insertCaptor.getAllValues();
    assertEquals(TENANT_ID, rows.get(0).getTenantId());
    assertEquals(MEMBER_ID, rows.get(0).getMemberId());
    assertEquals("token-a", rows.get(0).getToken());
    assertEquals("token-b", rows.get(1).getToken());
    // 未给创建时间的行走"现在"，不能落一个 null 进 NOT NULL 列
    assertNotNull(rows.get(1).getCreatedAt());
  }

  /** 空集合 = 只删不插（服务用它表达删除；删光后回填会重新处理这名客户）。 */
  @Test
  void replaceForMemberWithEmptyCollectionOnlyDeletes() {
    adapter.replaceForMember(MEMBER_ID, List.of(), LocalDateTime.now());

    verify(nameTokenMapper).delete(any());
    verify(nameTokenMapper, never()).insert(any(CstMemberNameTokenPo.class));
  }

  /** 插入中途失败：补偿删除（第二次 delete）并把原异常抛出，绝不留下半套索引。 */
  @Test
  void failedInsertCompensatesByDeletingAgainAndRethrows() {
    when(nameTokenMapper.insert(any(CstMemberNameTokenPo.class)))
        .thenThrow(new ApiException(500, "DB_DOWN", "插入失败"));

    ApiException failure = assertThrows(ApiException.class,
        () -> adapter.replaceForMember(MEMBER_ID, List.of(
            MemberNameToken.of(TENANT_ID, MEMBER_ID, "token-a")), LocalDateTime.now()));

    assertEquals("DB_DOWN", failure.getCode());
    // 一次正常删除 + 一次补偿删除
    verify(nameTokenMapper, times(2)).delete(any());
  }

  /** 删除行数原样回传（清理用例要把它写进审计详情）。 */
  @Test
  void deleteByMemberReturnsTheDeletedRowCount() {
    when(nameTokenMapper.delete(any())).thenReturn(3);

    assertEquals(3, adapter.deleteByMember(MEMBER_ID));
  }

  /** 显式传 tenantId 的关键词查询：token 个数由适配器按集合大小给，调用方不需要重复维护。 */
  @Test
  void findMemberIdsByAllTokensPassesTenantAndTokenCount() {
    when(nameTokenMapper.selectMemberIdsByAllTokens(TENANT_ID, List.of("a", "b"), 2))
        .thenReturn(List.of(5L, 6L));

    assertEquals(List.of(5L, 6L),
        adapter.findMemberIdsByAllTokens(TENANT_ID, new LinkedHashSet<>(List.of("a", "b"))));
  }

  /** 空 token 集合不查库（不得拼出非法的 {@code IN ()}）。 */
  @Test
  void findMemberIdsByAllTokensShortCircuitsOnEmptyTokens() {
    assertTrue(adapter.findMemberIdsByAllTokens(TENANT_ID, List.of()).isEmpty());
    verify(nameTokenMapper, never()).selectMemberIdsByAllTokens(anyLong(), anyCollection(), anyInt());
  }

  /** 存量回填的扫描与计数：null 结果一律归一成空集合/0（调度任务不该因 null 崩）。 */
  @Test
  void missingTokenScansNormaliseNullResults() {
    when(nameTokenMapper.selectTenantIdsWithMembers()).thenReturn(null);
    when(nameTokenMapper.selectMemberIdsMissingTokens(TENANT_ID, 0L, 200)).thenReturn(null);
    when(nameTokenMapper.countMembersMissingTokens(TENANT_ID)).thenReturn(0L);

    assertTrue(adapter.listTenantIdsWithMembers().isEmpty());
    assertTrue(adapter.findMemberIdsMissingTokens(TENANT_ID, 0L, 200).isEmpty());
    assertEquals(0L, adapter.countMembersMissingTokens(TENANT_ID));
    verify(nameTokenMapper).countMembersMissingTokens(TENANT_ID);
    // null 结果不得被当成"要写点什么"：扫描路径全程只读。
    verify(nameTokenMapper, never()).insert(any(CstMemberNameTokenPo.class));
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
}
