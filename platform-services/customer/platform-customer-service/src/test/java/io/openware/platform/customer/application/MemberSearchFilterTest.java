package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.crypto.AesGcmCipher;
import io.openware.common.util.SnowflakeIdGenerator;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.customer.domain.member.model.Member;
import io.openware.platform.customer.domain.member.model.MemberFilter;
import io.openware.platform.customer.domain.member.repository.MemberImBindingRepository;
import io.openware.platform.customer.domain.member.repository.MemberNameTokenRepository;
import io.openware.platform.customer.domain.member.repository.MemberRepository;
import io.openware.platform.customer.domain.member.repository.MemberRepository.MemberIdPage;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 组合检索**查询条件的装配**回归：客户号 / 姓名（盲索引）/ 手机号摘要 / IM 账号四条通道
 * 必须一次性装配进同一个 {@link MemberFilter}（这样分页与 total 才与过滤口径一致），
 * 且盲索引命中集合为空时该分支跳过、无租户上下文时姓名通道不参与。
 *
 * <p><b>分层后的职责划分</b>：token 与手机号摘要是<b>应用层的加密口径</b>，在本层算好后交给端口；
 * 「这些条件拼成什么 SQL」属于持久化细节，由 {@code MemberRepositoryAdapterTest} 用真实 Wrapper
 * 断言（那里才有 {@code getTargetSql()} 可看）。因此本类断言的是端口收到的条件对象，
 * 而不是 SQL 文本——原先本类 mock 的是 {@code MemberMapper} 并直接读 SQL，那是把适配器的职责
 * 写在了应用层用例里。
 */
class MemberSearchFilterTest {

  private static final long TENANT_ID = 100L;

  private final MemberRepository memberRepository = mock(MemberRepository.class);
  private final MemberNameTokenRepository nameTokenRepository = mock(MemberNameTokenRepository.class);
  private final MemberImBindingRepository imBindingRepository = mock(MemberImBindingRepository.class);
  private final PointAccountRepository pointAccountRepository = mock(PointAccountRepository.class);
  private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
  private final AesGcmCipher aesGcmCipher = mock(AesGcmCipher.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final MemberApplicationService service = new MemberApplicationService(
      memberRepository, nameTokenRepository, imBindingRepository, pointAccountRepository,
      idGenerator, aesGcmCipher, auditClient);

  @BeforeEach
  void setUpTenant() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, 1L, 1, List.of("member.pii.view")));
    when(memberRepository.listIds(any())).thenReturn(new MemberIdPage(List.of(), 0L));
  }

  @AfterEach
  void clearTenantContext() {
    TenantContextHolder.clear();
  }

  @Test
  void blankKeywordDoesNotFilter() {
    service.list(1, 10, "   ", null, null, TimeRange.none());

    MemberFilter filter = capturedFilter();
    assertNull(filter.keyword(), "全空白关键词不参与检索");
    assertNull(filter.phoneDigest());
    assertTrue(filter.nameMatchIds().isEmpty());
    verify(nameTokenRepository, never()).findMemberIdsByAllTokens(anyLong(), anyCollection());
  }

  @Test
  void phoneKeywordProducesTheSha256DigestInsteadOfBlindIndexTokens() {
    service.list(1, 10, "13800001111", null, null, TimeRange.none());

    MemberFilter filter = capturedFilter();
    assertEquals("13800001111", filter.keyword());
    assertEquals(NameBlindIndex.sha256Hex("13800001111"), filter.phoneDigest(),
        "纯数字关键词必须走手机号摘要精确匹配");
    assertTrue(filter.nameMatchIds().isEmpty());
    verify(nameTokenRepository, never()).findMemberIdsByAllTokens(anyLong(), anyCollection());
  }

  /** 姓名走盲索引：关键词切出的 token 全部命中，命中集合参与 id IN 过滤。 */
  @Test
  void nameKeywordUsesBlindIndexWithAllTokensRequired() {
    when(nameTokenRepository.findMemberIdsByAllTokens(eq(TENANT_ID), anyCollection()))
        .thenReturn(List.of(11L, 22L));

    service.list(1, 10, "张三", null, null, TimeRange.none());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<String>> tokens = ArgumentCaptor.forClass(Collection.class);
    verify(nameTokenRepository).findMemberIdsByAllTokens(eq(TENANT_ID), tokens.capture());
    assertEquals(new LinkedHashSet<>(NameBlindIndex.tokenize("张三")), new LinkedHashSet<>(tokens.getValue()));
    // 端口返回的候选 id 原样进入过滤条件（不是 token 字符串）
    assertEquals(List.of(11L, 22L), capturedFilter().nameMatchIds());
  }

  /** 盲索引无命中：跳过该分支（不得拼出非法的 IN ()），其余通道照常参与。 */
  @Test
  void emptyBlindIndexHitsSkipTheIdBranch() {
    when(nameTokenRepository.findMemberIdsByAllTokens(eq(TENANT_ID), anyCollection())).thenReturn(List.of());

    service.list(1, 10, "zzzz", null, null, TimeRange.none());

    MemberFilter filter = capturedFilter();
    assertTrue(filter.nameMatchIds().isEmpty());
    assertEquals("zzzz", filter.keyword(), "其余检索通道（客户号/IM 账号 LIKE）仍然参与");
  }

  @Test
  void keywordWithoutTenantContextSkipsBlindIndexInsteadOfFailing() {
    TenantContextHolder.clear();

    service.list(1, 10, "张三", null, null, TimeRange.none());

    verify(nameTokenRepository, never()).findMemberIdsByAllTokens(anyLong(), anyCollection());
    assertTrue(capturedFilter().nameMatchIds().isEmpty());
  }

  /** 其余筛选参数与分页一个都不被改写，并原样交给端口（偏移由页码换算）。 */
  @Test
  void everyOtherFilterAndPagingIsPassedThroughUnchanged() {
    when(nameTokenRepository.findMemberIdsByAllTokens(eq(TENANT_ID), anyCollection())).thenReturn(List.of());

    LocalDateTime from = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
    LocalDateTime to = LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000);
    service.list(3, 20, "张", 2L, "ACTIVE", new TimeRange(from, to), true, 7L);

    MemberFilter filter = capturedFilter();
    assertEquals(2L, filter.levelId());
    assertEquals("ACTIVE", filter.status());
    assertTrue(filter.onlyUnlinked());
    assertEquals(7L, filter.originStoreId());
    assertEquals(from, filter.joinedFrom());
    assertEquals(to, filter.joinedTo());
    // 第 3 页、每页 20 → 偏移 40，上限 20（端口只认偏移与上限）
    assertEquals(40L, filter.offset());
    assertEquals(20L, filter.limit());
  }

  /** 服务端 total 原样带回响应分页（不是"本页条数"）。 */
  @Test
  void listKeepsServerTotalSoPagingMatchesTheFilter() {
    when(memberRepository.listIds(any())).thenReturn(new MemberIdPage(List.of(5L), 42L));
    when(memberRepository.findAllById(List.of(5L))).thenReturn(List.of(member(5L)));
    when(nameTokenRepository.findMemberIdsByAllTokens(eq(TENANT_ID), anyCollection())).thenReturn(List.of(1L));

    Page<MemberDisplay> result = service.list(2, 20, "张", null, null, TimeRange.none());

    assertEquals(42L, result.getTotal());
    assertEquals(1, result.getRecords().size());
    assertEquals(5L, result.getRecords().get(0).member().id());
  }

  /** 非法门店筛选在发查询之前就被拒绝（与迁移前同一错误码）。 */
  @Test
  void invalidStoreFilterIsRejectedBeforeTouchingThePort() {
    io.openware.common.exception.ApiException failure =
        org.junit.jupiter.api.Assertions.assertThrows(io.openware.common.exception.ApiException.class,
            () -> service.list(1, 10, null, null, null, TimeRange.none(), false, 0L));

    assertEquals("STORE_FILTER_INVALID", failure.getCode());
    verify(memberRepository, never()).listIds(any());
  }

  private MemberFilter capturedFilter() {
    ArgumentCaptor<MemberFilter> captor = ArgumentCaptor.forClass(MemberFilter.class);
    verify(memberRepository).listIds(captor.capture());
    return captor.getValue();
  }

  private static Member member(Long id) {
    return new Member(id, TENANT_ID, null, null, null, null, null, "M" + id, null, null, null, null,
        Member.STATUS_ACTIVE, null, null, 0);
  }
}
