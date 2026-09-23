package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.crypto.AesGcmCipher;
import io.openware.common.exception.ApiException;
import io.openware.common.util.SnowflakeIdGenerator;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.infra.persistence.mapper.MemberMapper;
import io.openware.platform.customer.infra.persistence.mapper.MemberNameTokenMapper;
import io.openware.platform.customer.infra.persistence.mapper.PointAccountMapper;
import io.openware.platform.customer.infra.persistence.po.CstMemberNameTokenPo;
import io.openware.platform.customer.infra.persistence.po.CstMemberPo;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 客户建档的失败留痕回归（③ 遗留）+ 「防止同一 IM 用户生成多条客户」的三条路径。
 *
 * <p>建档失败（唯一键冲突/加密失败）必须落 FAILURE，且详情只保留账号 ID —— 姓名/手机号（明文或密文）
 * 绝不进审计；accountId/IM 命中既有客户时是**幂等返回**，不新建、不报 500、也不留「创建成功」的假痕迹。
 */
class MemberApplicationServiceTest {

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
  void createFailureRecordsFailureAuditWithErrorCode() {
    when(memberMapper.insert(any(CstMemberPo.class)))
        .thenThrow(new ApiException(409, "MEMBER_ALREADY_EXISTS", "该账号已存在客户档案"));

    assertThrows(ApiException.class, () -> service.create(5L, "张三", "13800001111", true));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("member.create", record.action());
    assertEquals("cst_member", record.resourceType());
    assertEquals("5", record.resourceId());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("MEMBER_ALREADY_EXISTS", record.errorCode());
    assertFalse(record.detailJson().contains("13800001111"), "手机号明文不得进审计详情");
    assertFalse(record.detailJson().contains("张三"), "姓名不得进审计详情");
  }

  /** 成功路径只留成功记录（不得同时落 FAILURE），且建档即写姓名/客户号盲索引。 */
  @Test
  void createSuccessRecordsSuccessAuditAndBuildsBlindIndex() {
    insertAssigningId(101L);

    service.create(5L, "张三", "13800001111", true);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("member.create", record.action());
    // 成功路径不显式标记结果，由 SDK buildBody 缺省为 SUCCESS；关键是不能被标成 FAILURE。
    assertNotEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertTokensInsertedSha256Hex();
  }

  @Test
  void createReturnsExistingCustomerWhenAccountIdAlreadyExists() {
    CstMemberPo existing = member(7L, 100L, "M7");
    when(memberMapper.selectList(any())).thenReturn(List.of(existing));

    CstMemberPo result = service.create(55L, "张三", "13800001111", false);

    assertEquals(7L, result.getId());
    verify(memberMapper, never()).insert(any(CstMemberPo.class));
    verify(auditClient, never()).recordAsync(any());
  }

  @Test
  void createReturnsExistingCustomerWhenImAccountAlreadyBound() {
    CstMemberPo existing = member(9L, 100L, "M9");
    when(memberMapper.selectList(any())).thenReturn(List.of(existing));

    CstMemberPo result = service.create(null, "张三", "13800001111", false, "im_71", "小明");

    assertEquals(9L, result.getId());
    verify(memberMapper, never()).insert(any(CstMemberPo.class));
    verify(auditClient, never()).recordAsync(any());
  }

  /** 待认领客户（accountId / imAccount 都为空）仍然建档，语义与迁移 V6 的「从未关联」一致。 */
  @Test
  void createWithoutAccountAndImStillCreatesUnclaimedCustomer() {
    when(memberMapper.selectList(any())).thenReturn(List.of());
    when(memberMapper.insert(any(CstMemberPo.class))).thenReturn(1);

    service.create(null, "张三", "", false, null, null);

    ArgumentCaptor<CstMemberPo> captor = ArgumentCaptor.forClass(CstMemberPo.class);
    verify(memberMapper).insert(captor.capture());
    assertNull(captor.getValue().getAccountId());
    assertNull(captor.getValue().getImAccount());
    assertNull(captor.getValue().getImBoundAt());
  }

  @Test
  void createWithImAccountStampsImBoundAt() {
    when(memberMapper.selectList(any())).thenReturn(List.of());
    when(memberMapper.insert(any(CstMemberPo.class))).thenReturn(1);

    service.create(null, "张三", "", false, " im_71 ", " 小明 ");

    ArgumentCaptor<CstMemberPo> captor = ArgumentCaptor.forClass(CstMemberPo.class);
    verify(memberMapper).insert(captor.capture());
    assertEquals("im_71", captor.getValue().getImAccount(), "IM 账号必须 trim");
    assertEquals("小明", captor.getValue().getImUsername());
    assertNotNull(captor.getValue().getImBoundAt());
  }

  /** 历史脏数据：同一 accountId 多条客户时取最早一条并返回，不新建、不抛异常。 */
  @Test
  void getOrCreateByAccountPicksEarliestRowWhenHistoryHasDuplicates() {
    when(memberMapper.selectList(any())).thenReturn(List.of(member(8L, 100L, "M8"), member(9L, 100L, "M9")));

    CstMemberPo result = service.getOrCreateByAccount(113L);

    assertEquals(8L, result.getId());
    verify(memberMapper, never()).insert(any(CstMemberPo.class));
  }

  @Test
  void getOrCreateByAccountCreatesWhenNothingMatches() {
    when(memberMapper.selectList(any())).thenReturn(List.of());
    when(memberMapper.insert(any(CstMemberPo.class))).thenReturn(1);

    service.getOrCreateByAccount(113L);

    ArgumentCaptor<CstMemberPo> captor = ArgumentCaptor.forClass(CstMemberPo.class);
    verify(memberMapper).insert(captor.capture());
    assertEquals(113L, captor.getValue().getAccountId());
  }

  @Test
  void bindImRejectsImAccountOwnedByAnotherCustomerWith409AndAuditsFailure() {
    TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1, List.of("member.pii.view")));
    CstMemberPo target = member(9L, 100L, "M9");
    when(memberMapper.selectById(9L)).thenReturn(target);
    when(memberMapper.selectList(any())).thenReturn(List.of(member(11L, 100L, "M11")));

    ApiException failure = assertThrows(ApiException.class, () -> service.bindIm(9L, "im_71", "小明", null));

    assertEquals(409, failure.getStatus());
    assertEquals("IM_ACCOUNT_ALREADY_BOUND", failure.getCode());
    assertTrue(failure.getMessage().contains("M11"), "消息必须带现有客户号");
    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("member.im.bind", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("IM_ACCOUNT_ALREADY_BOUND", record.errorCode());
    verify(memberMapper, never()).update(isNull(), any());
    verify(nameTokenMapper, never()).delete(any());
  }

  @Test
  void bindImUpdatesBindingRebuildsBlindIndexAndAuditsSuccess() {
    TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1, List.of("member.pii.view")));
    CstMemberPo target = member(9L, 100L, "M9");
    when(memberMapper.selectById(9L)).thenReturn(target);
    when(memberMapper.selectList(any())).thenReturn(List.of());
    when(aesGcmCipher.encrypt("张三")).thenReturn("v1:new-cipher");

    CstMemberPo result = service.bindIm(9L, " im_71 ", " 小明 ", "张三");

    assertEquals("im_71", result.getImAccount());
    assertEquals("小明", result.getImUsername());
    assertNotNull(result.getImBoundAt());
    assertEquals("v1:new-cipher", result.getNameCipher());
    verify(memberMapper).update(isNull(), any());
    // 改姓名必须重建 token 集合：先删后插
    verify(nameTokenMapper).delete(any());
    assertTokensInsertedSha256Hex();
    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("member.im.bind", record.action());
    assertNotEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    // 审计详情绝不带姓名
    assertFalse(record.detailJson().contains("张三"));
  }

  @Test
  void bindImRequiresImAccount() {
    assertThrows(ApiException.class, () -> service.bindIm(9L, "  ", "小明", null));
    verify(memberMapper, never()).update(isNull(), any());
  }

  private void assertTokensInsertedSha256Hex() {
    ArgumentCaptor<CstMemberNameTokenPo> captor = ArgumentCaptor.forClass(CstMemberNameTokenPo.class);
    verify(nameTokenMapper, atLeastOnce()).insert(captor.capture());
    assertFalse(captor.getAllValues().isEmpty());
    captor.getAllValues().forEach(row -> assertEquals(64, row.getToken().length()));
  }

  /** 真实 insert 会把自增主键写回 PO；mock 里必须显式回填，否则 token 重建会因 id 为空而短路。 */
  private void insertAssigningId(Long id) {
    when(memberMapper.insert(any(CstMemberPo.class))).thenAnswer(invocation -> {
      CstMemberPo po = invocation.getArgument(0);
      po.setId(id);
      return 1;
    });
  }

  private AuditClient.AuditRecord capturedAudit() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    return captor.getValue();
  }

  private static CstMemberPo member(Long id, Long tenantId, String memberNo) {
    CstMemberPo po = new CstMemberPo();
    po.setId(id);
    po.setTenantId(tenantId);
    po.setMemberNo(memberNo);
    return po;
  }
}
