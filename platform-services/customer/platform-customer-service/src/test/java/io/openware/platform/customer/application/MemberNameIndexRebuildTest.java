package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.crypto.AesGcmCipher;
import io.openware.common.exception.ApiException;
import io.openware.common.util.SnowflakeIdGenerator;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.domain.member.model.Member;
import io.openware.platform.customer.domain.member.model.MemberNameToken;
import io.openware.platform.customer.domain.member.repository.MemberImBindingRepository;
import io.openware.platform.customer.domain.member.repository.MemberNameTokenRepository;
import io.openware.platform.customer.domain.member.repository.MemberRepository;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 姓名盲索引**存量回填**的回归：只处理缺 token 的客户、逐条设置租户上下文、单条失败不中断整批、
 * 游标必推进（不会因某条客户反复失败而卡死）、重建是「先删后插」的幂等操作。
 *
 * <p>本类只驱动领域端口：扫描/计数走 {@code MemberNameTokenRepository}，读档案走
 * {@code MemberRepository}，重建走 {@code replaceForMember}（"先删后插 + 失败补偿"由适配器负责，
 * 适配器测试与集成测试各自钉住自己那一半）。
 */
class MemberNameIndexRebuildTest {

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

  @AfterEach
  void clearTenantContext() {
    TenantContextHolder.clear();
  }

  @Test
  void rebuildOnlyProcessesMembersMissingTokensAndSetsTenantContextPerRow() {
    when(nameTokenRepository.findMemberIdsMissingTokens(TENANT_ID, 0L, 200)).thenReturn(List.of(1L, 2L));
    when(nameTokenRepository.findMemberIdsMissingTokens(TENANT_ID, 2L, 200)).thenReturn(List.of());
    when(nameTokenRepository.countMembersMissingTokens(TENANT_ID)).thenReturn(0L);
    List<Long> tenantSeenWhileWriting = new ArrayList<>();
    when(memberRepository.findById(any())).thenAnswer(invocation -> {
      tenantSeenWhileWriting.add(TenantContextHolder.tenantIdOrNull());
      return Optional.of(member(invocation.getArgument(0), "M" + invocation.getArgument(0)));
    });

    NameIndexRebuildResult result = service.rebuildNameIndexForTenant(TENANT_ID);

    assertEquals(2, result.scanned());
    assertEquals(2, result.rebuilt());
    assertEquals(0, result.failed());
    assertEquals(0L, result.remaining());
    // 逐条写之前必须设置该客户的租户上下文，否则会被租户拦截器整批拒绝
    assertEquals(List.of(TENANT_ID, TENANT_ID), tenantSeenWhileWriting);
    // 调用前的线程上下文（无）必须被还原，不能把租户泄漏到调用方线程
    assertNull(TenantContextHolder.get());
    verify(nameTokenRepository, times(2)).replaceForMember(any(), any());
  }

  @Test
  void singleFailureDoesNotAbortTheBatchAndIsCounted() {
    when(nameTokenRepository.findMemberIdsMissingTokens(TENANT_ID, 0L, 200)).thenReturn(List.of(1L, 2L, 3L));
    when(nameTokenRepository.countMembersMissingTokens(TENANT_ID)).thenReturn(1L);
    when(memberRepository.findById(any())).thenAnswer(invocation -> {
      Long memberId = invocation.getArgument(0);
      if (memberId == 2L) {
        throw new ApiException(500, "CIPHER_BROKEN", "解密失败");
      }
      return Optional.of(member(memberId, "M" + memberId));
    });

    NameIndexRebuildResult result = service.rebuildNameIndexForTenant(TENANT_ID);

    assertEquals(3, result.scanned());
    assertEquals(2, result.rebuilt());
    assertEquals(1, result.failed());
    assertEquals(1L, result.remaining());
    // 失败客户之后的客户仍然被处理（不中断整批）
    verify(memberRepository).findById(3L);
  }

  /** token 集合为空（姓名/客户号都不可用）时也要推进游标：不能反复扫描同一行。 */
  @Test
  void memberWithoutAnyTokenTextIsNotRescannedForever() {
    when(nameTokenRepository.findMemberIdsMissingTokens(TENANT_ID, 0L, 200)).thenReturn(List.of(1L));
    when(nameTokenRepository.countMembersMissingTokens(TENANT_ID)).thenReturn(0L);
    when(memberRepository.findById(any())).thenReturn(Optional.of(member(1L, null)));

    NameIndexRebuildResult result = service.rebuildNameIndexForTenant(TENANT_ID);

    assertEquals(1, result.scanned());
    assertEquals(1, result.rebuilt());
    // 空 token 集合仍然发一次"重建"（适配器先删后插，删完即结束）——不能因为集合为空就跳过删除，
    // 否则删掉姓名的客户会留下旧 token，按旧姓名仍能搜到。
    assertEmptyTokenWrite(1L);
    // 小批量后循环结束：不会因为「该客户没有 token 行」而反复拉取同一批
    verify(nameTokenRepository, times(1)).findMemberIdsMissingTokens(TENANT_ID, 0L, 200);
  }

  @Test
  void fullBatchAdvancesCursorByLastMemberId() {
    when(nameTokenRepository.findMemberIdsMissingTokens(TENANT_ID, 0L, 200))
        .thenReturn(LongStream.rangeClosed(1, 200).boxed().toList());
    when(nameTokenRepository.findMemberIdsMissingTokens(TENANT_ID, 200L, 200)).thenReturn(List.of(201L, 202L));
    when(nameTokenRepository.countMembersMissingTokens(TENANT_ID)).thenReturn(0L);
    when(memberRepository.findById(any()))
        .thenAnswer(invocation -> Optional.of(member(invocation.getArgument(0), "M1")));

    NameIndexRebuildResult result = service.rebuildNameIndexForTenant(TENANT_ID);

    assertEquals(202, result.scanned());
    assertEquals(202, result.rebuilt());
    // 满批（200）后用「本批最后一个 id」作为游标继续，不是从头再来
    verify(nameTokenRepository).findMemberIdsMissingTokens(TENANT_ID, 200L, 200);
  }

  /** 重建幂等：同一客户重建两次，每次都「先删后插」，插入条数完全一致（不会累积重复 token）。 */
  @Test
  void tokenRebuildIsIdempotentDeleteThenInsert() {
    when(nameTokenRepository.findMemberIdsMissingTokens(anyLong(), anyLong(), anyInt())).thenReturn(List.of(1L));
    when(nameTokenRepository.countMembersMissingTokens(anyLong())).thenReturn(0L);
    when(memberRepository.findById(any())).thenReturn(Optional.of(member(1L, "M1")));

    service.rebuildNameIndexForTenant(TENANT_ID);
    service.rebuildNameIndexForTenant(TENANT_ID);

    verify(nameTokenRepository, times(2)).replaceForMember(eq(1L), any());
    // 每次 3 个 token（m / 1 / m1），两次共 6 行
    assertEquals(6, allTokenRows().size());
  }

  /** 失败补偿：插入过程中失败要再删一次，退回「零 token 行」状态，让下一轮回填重新处理。 */
  @Test
  void failedTokenInsertIsCountedAndPropagatesFromTheAdapter() {
    when(nameTokenRepository.findMemberIdsMissingTokens(TENANT_ID, 0L, 200)).thenReturn(List.of(1L));
    when(nameTokenRepository.countMembersMissingTokens(TENANT_ID)).thenReturn(1L);
    when(memberRepository.findById(any())).thenReturn(Optional.of(member(1L, "M1")));
    // 端口实现负责"补偿删除 + 原样抛出"（见 MemberNameTokenRepositoryAdapter）；服务侧只记 failed 并继续。
    org.mockito.Mockito.doThrow(new ApiException(500, "DB_DOWN", "插入失败"))
        .when(nameTokenRepository).replaceForMember(any(), any());

    NameIndexRebuildResult result = service.rebuildNameIndexForTenant(TENANT_ID);

    assertEquals(1, result.failed());
    assertEquals(0, result.rebuilt());
    verify(nameTokenRepository, atLeastOnce()).replaceForMember(any(), any());
  }

  @Test
  void rebuildAllTenantsContinuesWhenOneTenantFails() {
    when(nameTokenRepository.listTenantIdsWithMembers()).thenReturn(List.of(100L, 200L));
    when(nameTokenRepository.findMemberIdsMissingTokens(anyLong(), anyLong(), anyInt()))
        .thenAnswer(invocation -> {
          long tenantId = invocation.getArgument(0);
          if (tenantId == 100L) {
            throw new ApiException(500, "DB_DOWN", "查询失败");
          }
          return List.of();
        });
    when(nameTokenRepository.countMembersMissingTokens(200L)).thenReturn(0L);

    List<NameIndexRebuildResult> results = service.rebuildNameIndexAllTenants();

    // 租户 100 失败被吞掉，租户 200 仍被处理
    assertEquals(1, results.size());
    assertEquals(200L, results.get(0).tenantId());
  }

  /** 无租户存在的极端情形（空列表）不得 NPE：调度任务每轮都会走这条路径。 */
  @Test
  void rebuildAllTenantsHandlesNoTenants() {
    when(nameTokenRepository.listTenantIdsWithMembers()).thenReturn(List.of());

    assertEquals(0, service.rebuildNameIndexAllTenants().size());
    verify(nameTokenRepository, never()).findMemberIdsMissingTokens(anyLong(), anyLong(), anyInt());
  }

  @SuppressWarnings("unchecked")
  private void assertEmptyTokenWrite(Long memberId) {
    ArgumentCaptor<Collection<MemberNameToken>> captor = ArgumentCaptor.forClass(Collection.class);
    verify(nameTokenRepository).replaceForMember(eq(memberId), captor.capture());
    assertEquals(0, captor.getValue().size());
  }

  @SuppressWarnings("unchecked")
  private List<MemberNameToken> allTokenRows() {
    ArgumentCaptor<Collection<MemberNameToken>> captor = ArgumentCaptor.forClass(Collection.class);
    verify(nameTokenRepository, times(2)).replaceForMember(any(), captor.capture());
    List<MemberNameToken> rows = new ArrayList<>();
    captor.getAllValues().forEach(rows::addAll);
    return rows;
  }

  private static Member member(Long id, String memberNo) {
    return new Member(id, TENANT_ID, null, null, null, null, null, memberNo, null, null, null, null,
        Member.STATUS_ACTIVE, null, null, 0);
  }
}
