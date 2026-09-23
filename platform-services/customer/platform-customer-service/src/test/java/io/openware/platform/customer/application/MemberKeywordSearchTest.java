package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.crypto.AesGcmCipher;
import io.openware.common.util.SnowflakeIdGenerator;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.customer.infra.persistence.mapper.MemberMapper;
import io.openware.platform.customer.infra.persistence.mapper.MemberNameTokenMapper;
import io.openware.platform.customer.infra.persistence.mapper.PointAccountMapper;
import io.openware.platform.customer.infra.persistence.po.CstMemberPo;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code applyKeyword} 组合检索的 SQL 形态回归：客户号 / 姓名（盲索引）/ 手机号摘要 / IM 账号四条通道
 * 必须拼进**同一个 OR 与同一个 WHERE**（这样分页与 total 才与过滤口径一致），
 * 且盲索引命中集合为空时不得拼出非法的 {@code IN ()}。
 */
class MemberKeywordSearchTest {

  private final MemberMapper memberMapper = mock(MemberMapper.class);
  private final PointAccountMapper pointAccountMapper = mock(PointAccountMapper.class);
  private final MemberNameTokenMapper nameTokenMapper = mock(MemberNameTokenMapper.class);
  private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
  private final AesGcmCipher aesGcmCipher = mock(AesGcmCipher.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final MemberApplicationService service = new MemberApplicationService(
      memberMapper, pointAccountMapper, nameTokenMapper, idGenerator, aesGcmCipher, auditClient);

  @BeforeEach
  void setUpTenant() {
    TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1, List.of("member.pii.view")));
    doAnswer(invocation -> {
      Page<CstMemberPo> page = invocation.getArgument(0);
      page.setRecords(List.of());
      return page;
    }).when(memberMapper).selectPage(any(), any());
  }

  @AfterEach
  void clearTenantContext() {
    TenantContextHolder.clear();
  }

  @Test
  void blankKeywordDoesNotFilter() {
    service.list(1, 10, "   ", null, null, TimeRange.none());

    assertFalse(capturedWhereSql().contains("member_no"), "全空白关键词不参与检索");
    verify(nameTokenMapper, never()).selectMemberIdsByAllTokens(anyLong(), anyCollection(), anyInt());
  }

  @Test
  void keywordSearchesMemberNoPhoneAndImInOneOrFilter() {
    when(nameTokenMapper.selectMemberIdsByAllTokens(eq(100L), anyCollection(), anyInt())).thenReturn(List.of());

    service.list(1, 10, "13800001111", null, null, TimeRange.none());

    String sql = capturedWhereSql();
    assertTrue(sql.contains("member_no"), sql);
    assertTrue(sql.contains("im_account"), sql);
    assertTrue(sql.contains("im_username"), sql);
    assertTrue(sql.contains("phone_digest"), "纯数字关键词必须走手机号摘要精确匹配");
  }

  /** 姓名走盲索引：关键词 token 全命中（tokenCount = token 个数），命中集合参与 id IN 过滤。 */
  @Test
  void keywordUsesBlindIndexTokensWithAllTokensRequired() {
    when(nameTokenMapper.selectMemberIdsByAllTokens(eq(100L), anyCollection(), anyInt()))
        .thenReturn(List.of(11L, 22L));

    service.list(1, 10, "张三", null, null, TimeRange.none());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<String>> tokens = ArgumentCaptor.forClass(Collection.class);
    verify(nameTokenMapper).selectMemberIdsByAllTokens(eq(100L), tokens.capture(), eq(3));
    assertEquals(new LinkedHashSet<>(NameBlindIndex.tokenize("张三")), new LinkedHashSet<>(tokens.getValue()));
    String sql = capturedWhereSql();
    assertTrue(sql.contains("id IN"), sql);
  }

  /** 盲索引无命中：跳过该 OR 分支（不能拼出非法的 IN ()），其余通道照常参与。 */
  @Test
  void emptyBlindIndexHitsSkipTheInBranch() {
    when(nameTokenMapper.selectMemberIdsByAllTokens(eq(100L), anyCollection(), anyInt())).thenReturn(List.of());

    service.list(1, 10, "zzzz", null, null, TimeRange.none());

    String sql = capturedWhereSql();
    assertFalse(sql.contains("id IN"), sql);
    assertTrue(sql.contains("member_no"), sql);
  }

  @Test
  void keywordWithoutTenantContextSkipsBlindIndexInsteadOfFailing() {
    TenantContextHolder.clear();

    service.list(1, 10, "张三", null, null, TimeRange.none());

    verify(nameTokenMapper, never())
        .selectMemberIdsByAllTokens(anyLong(), anyCollection(), anyInt());
  }

  @Test
  void listKeepsServerTotalSoPagingMatchesTheFilter() {
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      Page<CstMemberPo> page = invocation.getArgument(0);
      page.setRecords(List.of(new CstMemberPo()));
      page.setTotal(42L);
      return page;
    }).when(memberMapper).selectPage(any(), any());
    when(nameTokenMapper.selectMemberIdsByAllTokens(eq(100L), anyCollection(), anyInt())).thenReturn(List.of(1L));

    Page<CstMemberPo> result = service.list(2, 20, "张", null, null, TimeRange.none());

    assertEquals(42L, result.getTotal());
    assertEquals(1, result.getRecords().size());
  }

  @SuppressWarnings("unchecked")
  private String capturedWhereSql() {
    ArgumentCaptor<Wrapper<CstMemberPo>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(memberMapper).selectPage(any(), captor.capture());
    return captor.getValue().getTargetSql();
  }
}
