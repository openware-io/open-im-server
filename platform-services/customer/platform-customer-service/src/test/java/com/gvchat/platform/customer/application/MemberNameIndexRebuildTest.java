package com.gvchat.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.crypto.AesGcmCipher;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.util.SnowflakeIdGenerator;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.customer.infra.persistence.mapper.MemberMapper;
import com.gvchat.platform.customer.infra.persistence.mapper.MemberNameTokenMapper;
import com.gvchat.platform.customer.infra.persistence.mapper.PointAccountMapper;
import com.gvchat.platform.customer.infra.persistence.po.CstMemberNameTokenPo;
import com.gvchat.platform.customer.infra.persistence.po.CstMemberPo;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 姓名盲索引**存量回填**的回归：只处理缺 token 的客户、逐条设置租户上下文、单条失败不中断整批、
 * 游标必推进（不会因某条客户反复失败而卡死）、重建是「先删后插」的幂等操作。
 */
class MemberNameIndexRebuildTest {

  private static final long TENANT_ID = 100L;

  private final MemberMapper memberMapper = mock(MemberMapper.class);
  private final PointAccountMapper pointAccountMapper = mock(PointAccountMapper.class);
  private final MemberNameTokenMapper nameTokenMapper = mock(MemberNameTokenMapper.class);
  private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
  private final AesGcmCipher aesGcmCipher = mock(AesGcmCipher.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final MemberApplicationService service = new MemberApplicationService(
      memberMapper, pointAccountMapper, nameTokenMapper, idGenerator, aesGcmCipher, auditClient);

  @AfterEach
  void clearTenantContext() {
    TenantContextHolder.clear();
  }

  @Test
  void rebuildOnlyProcessesMembersMissingTokensAndSetsTenantContextPerRow() {
    when(nameTokenMapper.selectMemberIdsMissingTokens(TENANT_ID, 0L, 200)).thenReturn(List.of(1L, 2L));
    when(nameTokenMapper.selectMemberIdsMissingTokens(TENANT_ID, 2L, 200)).thenReturn(List.of());
    when(nameTokenMapper.countMembersMissingTokens(TENANT_ID)).thenReturn(0L);
    List<Long> tenantSeenWhileWriting = new ArrayList<>();
    when(memberMapper.selectById(any())).thenAnswer(invocation -> {
      tenantSeenWhileWriting.add(TenantContextHolder.tenantIdOrNull());
      return member(invocation.getArgument(0), "M" + invocation.getArgument(0));
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
    verify(nameTokenMapper, times(2)).delete(any());
  }

  @Test
  void singleFailureDoesNotAbortTheBatchAndIsCounted() {
    when(nameTokenMapper.selectMemberIdsMissingTokens(TENANT_ID, 0L, 200)).thenReturn(List.of(1L, 2L, 3L));
    when(nameTokenMapper.countMembersMissingTokens(TENANT_ID)).thenReturn(1L);
    when(memberMapper.selectById(any())).thenAnswer(invocation -> {
      Long memberId = invocation.getArgument(0);
      if (memberId == 2L) {
        throw new ApiException(500, "CIPHER_BROKEN", "解密失败");
      }
      return member(memberId, "M" + memberId);
    });

    NameIndexRebuildResult result = service.rebuildNameIndexForTenant(TENANT_ID);

    assertEquals(3, result.scanned());
    assertEquals(2, result.rebuilt());
    assertEquals(1, result.failed());
    assertEquals(1L, result.remaining());
    // 失败客户之后的客户仍然被处理（不中断整批）
    verify(memberMapper).selectById(3L);
  }

  /** token 集合为空（姓名/客户号都不可用）时也要推进游标：不能反复扫描同一行。 */
  @Test
  void memberWithoutAnyTokenTextIsNotRescannedForever() {
    when(nameTokenMapper.selectMemberIdsMissingTokens(TENANT_ID, 0L, 200)).thenReturn(List.of(1L));
    when(nameTokenMapper.countMembersMissingTokens(TENANT_ID)).thenReturn(0L);
    when(memberMapper.selectById(any())).thenReturn(member(1L, null));

    NameIndexRebuildResult result = service.rebuildNameIndexForTenant(TENANT_ID);

    assertEquals(1, result.scanned());
    assertEquals(1, result.rebuilt());
    verify(nameTokenMapper, never()).insert(any(CstMemberNameTokenPo.class));
    // 小批量后循环结束：不会因为「该客户没有 token 行」而反复拉取同一批
    verify(nameTokenMapper, times(1)).selectMemberIdsMissingTokens(TENANT_ID, 0L, 200);
  }

  @Test
  void fullBatchAdvancesCursorByLastMemberId() {
    when(nameTokenMapper.selectMemberIdsMissingTokens(TENANT_ID, 0L, 200))
        .thenReturn(LongStream.rangeClosed(1, 200).boxed().toList());
    when(nameTokenMapper.selectMemberIdsMissingTokens(TENANT_ID, 200L, 200)).thenReturn(List.of(201L, 202L));
    when(nameTokenMapper.countMembersMissingTokens(TENANT_ID)).thenReturn(0L);
    when(memberMapper.selectById(any())).thenAnswer(invocation -> member(invocation.getArgument(0), "M1"));

    NameIndexRebuildResult result = service.rebuildNameIndexForTenant(TENANT_ID);

    assertEquals(202, result.scanned());
    assertEquals(202, result.rebuilt());
    // 满批（200）后用「本批最后一个 id」作为游标继续，不是从头再来
    verify(nameTokenMapper).selectMemberIdsMissingTokens(TENANT_ID, 200L, 200);
  }

  /** 重建幂等：同一客户重建两次，每次都「先删后插」，插入条数完全一致（不会累积重复 token）。 */
  @Test
  void tokenRebuildIsIdempotentDeleteThenInsert() {
    when(nameTokenMapper.selectMemberIdsMissingTokens(anyLong(), anyLong(), anyInt())).thenReturn(List.of(1L));
    when(nameTokenMapper.countMembersMissingTokens(anyLong())).thenReturn(0L);
    when(memberMapper.selectById(any())).thenReturn(member(1L, "M1"));

    service.rebuildNameIndexForTenant(TENANT_ID);
    service.rebuildNameIndexForTenant(TENANT_ID);

    verify(nameTokenMapper, times(2)).delete(any());
    // 每次 3 个 token（m / 1 / m1），两次共 6 次插入
    verify(nameTokenMapper, times(6)).insert(any(CstMemberNameTokenPo.class));
  }

  /** 失败补偿：插入过程中失败要再删一次，退回「零 token 行」状态，让下一轮回填重新处理。 */
  @Test
  void failedTokenInsertCompensatesByDeletingPartialIndexAndRethrows() {
    when(nameTokenMapper.selectMemberIdsMissingTokens(TENANT_ID, 0L, 200)).thenReturn(List.of(1L));
    when(nameTokenMapper.countMembersMissingTokens(TENANT_ID)).thenReturn(1L);
    when(memberMapper.selectById(any())).thenReturn(member(1L, "M1"));
    when(nameTokenMapper.insert(any(CstMemberNameTokenPo.class)))
        .thenThrow(new ApiException(500, "DB_DOWN", "插入失败"));

    NameIndexRebuildResult result = service.rebuildNameIndexForTenant(TENANT_ID);

    assertEquals(1, result.failed());
    assertEquals(0, result.rebuilt());
    // 一次正常删除 + 一次补偿删除
    verify(nameTokenMapper, times(2)).delete(any());
    verify(nameTokenMapper, atLeastOnce()).insert(any(CstMemberNameTokenPo.class));
  }

  @Test
  void rebuildAllTenantsContinuesWhenOneTenantFails() {
    when(nameTokenMapper.selectTenantIdsWithMembers()).thenReturn(List.of(100L, 200L));
    when(nameTokenMapper.selectMemberIdsMissingTokens(anyLong(), anyLong(), anyInt()))
        .thenAnswer(invocation -> {
          long tenantId = invocation.getArgument(0);
          if (tenantId == 100L) {
            throw new ApiException(500, "DB_DOWN", "查询失败");
          }
          return List.of();
        });
    when(nameTokenMapper.countMembersMissingTokens(200L)).thenReturn(0L);

    List<NameIndexRebuildResult> results = service.rebuildNameIndexAllTenants();

    // 租户 100 失败被吞掉，租户 200 仍被处理
    assertEquals(1, results.size());
    assertEquals(200L, results.get(0).tenantId());
  }

  private static CstMemberPo member(Long id, String memberNo) {
    CstMemberPo po = new CstMemberPo();
    po.setId(id);
    po.setTenantId(TENANT_ID);
    po.setMemberNo(memberNo);
    return po;
  }
}
