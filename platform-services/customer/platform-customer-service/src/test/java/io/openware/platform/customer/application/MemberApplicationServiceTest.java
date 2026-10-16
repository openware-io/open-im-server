package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import io.openware.platform.customer.domain.member.model.Member;
import io.openware.platform.customer.domain.member.model.MemberNameToken;
import io.openware.platform.customer.domain.member.repository.MemberImBindingRepository;
import io.openware.platform.customer.domain.member.repository.MemberNameTokenRepository;
import io.openware.platform.customer.domain.member.repository.MemberRepository;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/**
 * 客户建档的失败留痕回归（③ 遗留）+ 「防止同一 IM 用户生成多条客户」的三条路径。
 *
 * <p>建档失败（唯一键冲突/加密失败）必须落 FAILURE，且详情只保留账号 ID —— 姓名/手机号（明文或密文）
 * 绝不进审计；accountId/IM 命中既有客户时是**幂等返回**，不新建、不报 500、也不留「创建成功」的假痕迹。
 *
 * <p><b>本类只驱动领域端口</b>（{@code MemberRepository} / {@code MemberNameTokenRepository} /
 * {@code MemberImBindingRepository} / {@code PointAccountRepository}）。原先它 mock 的是
 * {@code MemberMapper} / {@code MemberNameTokenMapper}，于是"应用服务用的到底是端口还是 Mapper"
 * 这件事在编译期没有约束；现在桩替换成端口，服务再想直接摸 Mapper 就编译不过。
 * SQL 形状与列集合由 {@code MemberRepositoryAdapterTest} 覆盖（那里才有真实的 Wrapper 可断言）。
 */
class MemberApplicationServiceTest {

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
  void createFailureRecordsFailureAuditWithErrorCode() {
    when(memberRepository.insert(any(Member.class)))
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

    MemberDisplay created = service.create(5L, "张三", "13800001111", true);

    assertEquals(101L, created.member().id());
    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("member.create", record.action());
    // 成功路径不显式标记结果，由 SDK buildBody 缺省为 SUCCESS；关键是不能被标成 FAILURE。
    assertNotEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertTokensWrittenSha256Hex(101L);
  }

  @Test
  void createReturnsExistingCustomerWhenAccountIdAlreadyExists() {
    Member existing = member(7L, 100L, "M7");
    when(memberRepository.findEarliestByAccountId(55L)).thenReturn(Optional.of(existing));

    MemberDisplay result = service.create(55L, "张三", "13800001111", false);

    assertEquals(7L, result.member().id());
    verify(memberRepository, never()).insert(any(Member.class));
    verify(auditClient, never()).recordAsync(any());
  }

  @Test
  void createReturnsExistingCustomerWhenImAccountAlreadyBound() {
    Member existing = member(9L, 100L, "M9");
    when(memberRepository.findEarliestByImAccount("im_71")).thenReturn(Optional.of(existing));

    MemberDisplay result = service.create(null, "张三", "13800001111", false, "im_71", "小明");

    assertEquals(9L, result.member().id());
    verify(memberRepository, never()).insert(any(Member.class));
    verify(auditClient, never()).recordAsync(any());
  }

  /** 待认领客户（accountId / imAccount 都为空）仍然建档，语义与迁移 V6 的「从未关联」一致。 */
  @Test
  void createWithoutAccountAndImStillCreatesUnclaimedCustomer() {
    when(memberRepository.insert(any(Member.class))).thenAnswer(invocation -> {
      Member candidate = invocation.getArgument(0);
      return withId(candidate, 1L);
    });

    service.create(null, "张三", "", false, null, null);

    ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
    verify(memberRepository).insert(captor.capture());
    assertNull(captor.getValue().accountId());
    assertNull(captor.getValue().imAccount());
    assertNull(captor.getValue().imBoundAt());
  }

  @Test
  void createWithImAccountStampsImBoundAt() {
    when(memberRepository.insert(any(Member.class))).thenAnswer(invocation -> {
      Member candidate = invocation.getArgument(0);
      return withId(candidate, 1L);
    });

    service.create(null, "张三", "", false, " im_71 ", " 小明 ");

    ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
    verify(memberRepository).insert(captor.capture());
    assertEquals("im_71", captor.getValue().imAccount(), "IM 账号必须 trim");
    assertEquals("小明", captor.getValue().imUsername());
    assertNotNull(captor.getValue().imBoundAt());
  }

  /** 建档时就回填账号在统一账号模型里的 IM 信息（读不到就保持空，不伪造）。 */
  @Test
  void createBackfillsImIdentityFromAccountDirectory() {
    when(imBindingRepository.findImAccountByAccountId(113L)).thenReturn("im_3");
    when(imBindingRepository.findImNicknameByAccountId(113L)).thenReturn("小张");
    when(memberRepository.insert(any(Member.class))).thenAnswer(invocation -> {
      Member candidate = invocation.getArgument(0);
      return withId(candidate, 12L);
    });

    service.create(100L, 113L, "张三", "", false, null, null);

    ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
    verify(memberRepository).insert(captor.capture());
    assertEquals("im_3", captor.getValue().imAccount());
    assertEquals("小张", captor.getValue().imUsername());
  }

  /** 历史脏数据：同一 accountId 多条客户时取最早一条并返回，不新建、不抛异常。 */
  @Test
  void getOrCreateByAccountReturnsTheEarliestRowWhenHistoryHasDuplicates() {
    // 「取最早一条」是端口契约（适配器按 id 升序 LIMIT 2 并打 WARN），这里断言服务原样采用端口给的赢家。
    when(memberRepository.findEarliestByAccountId(113L)).thenReturn(Optional.of(member(8L, 100L, "M8")));

    MemberDisplay result = service.getOrCreateByAccount(113L);

    assertEquals(8L, result.member().id());
    verify(memberRepository, never()).insert(any(Member.class));
  }

  @Test
  void getOrCreateByAccountCreatesWhenNothingMatches() {
    when(memberRepository.findEarliestByAccountId(113L)).thenReturn(Optional.empty());
    when(memberRepository.insert(any(Member.class))).thenAnswer(invocation -> {
      Member candidate = invocation.getArgument(0);
      return withId(candidate, 1L);
    });

    MemberDisplay result = service.getOrCreateByAccount(113L);

    assertEquals(113L, result.member().accountId());
  }

  @Test
  void getOrCreateByAccountReturnsImWinnerAfterConcurrentUniqueConflict() {
    Member winner = member(12L, null, "M12").withImBinding("im_3", null, LocalDateTime.now(), null);
    when(imBindingRepository.findImAccountByAccountId(113L)).thenReturn("im_3");
    when(memberRepository.findEarliestByImAccount("im_3")).thenReturn(Optional.of(winner));
    // 唯一键竞争的赢家由第二轮读取（或 IM 账号兜底解析）发现：账号直查永远为空。
    when(memberRepository.findEarliestByAccountId(113L)).thenReturn(Optional.empty());
    when(memberRepository.insert(any(Member.class)))
        .thenThrow(new DuplicateKeyException("tenant + im conflict"));

    MemberDisplay result = service.getOrCreateByAccount(100L, 113L);

    assertEquals(12L, result.member().id());
  }

  @Test
  void bindImRejectsImAccountOwnedByAnotherCustomerWith409AndAuditsFailure() {
    TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1, List.of("member.pii.view")));
    when(memberRepository.findById(9L)).thenReturn(Optional.of(member(9L, 100L, "M9")));
    when(memberRepository.findEarliestByImAccount("im_71"))
        .thenReturn(Optional.of(member(11L, 100L, "M11")));

    ApiException failure = assertThrows(ApiException.class, () -> service.bindIm(9L, "im_71", "小明", null));

    assertEquals(409, failure.getStatus());
    assertEquals("IM_ACCOUNT_ALREADY_BOUND", failure.getCode());
    assertTrue(failure.getMessage().contains("M11"), "消息必须带现有客户号");
    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("member.im.bind", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("IM_ACCOUNT_ALREADY_BOUND", record.errorCode());
    verify(memberRepository, never()).updateImBinding(anyLong(), any(), any(), any(), any());
    verify(nameTokenRepository, never()).replaceForMember(anyLong(), any());
  }

  @Test
  void bindImUpdatesBindingRebuildsBlindIndexAndAuditsSuccess() {
    TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1, List.of("member.pii.view")));
    when(memberRepository.findById(9L)).thenReturn(Optional.of(member(9L, 100L, "M9")));
    when(memberRepository.findEarliestByImAccount("im_71")).thenReturn(Optional.empty());
    when(memberRepository.updateImBinding(eq(9L), eq("im_71"), eq("小明"), any(), eq("v1:new-cipher")))
        .thenReturn(true);
    when(aesGcmCipher.encrypt("张三")).thenReturn("v1:new-cipher");

    MemberDisplay result = service.bindIm(9L, " im_71 ", " 小明 ", "张三");

    assertEquals("im_71", result.member().imAccount());
    assertEquals("小明", result.member().imUsername());
    assertNotNull(result.member().imBoundAt());
    assertEquals("v1:new-cipher", result.member().nameCipher());
    // 改姓名必须重建 token 集合（先删后插由适配器保证，这里断言服务确实发起了重建）。
    assertTokensWrittenSha256Hex(9L);
    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("member.im.bind", record.action());
    assertNotEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    // 审计详情绝不带姓名
    assertFalse(record.detailJson().contains("张三"));
  }

  @Test
  void bindImRequiresImAccount() {
    assertThrows(ApiException.class, () -> service.bindIm(9L, "  ", "小明", null));
    verify(memberRepository, never()).updateImBinding(anyLong(), any(), any(), any(), any());
  }

  /** 绑定到不存在的客户 → 404，且不触碰任何写路径。 */
  @Test
  void bindImRejectsMissingCustomer() {
    when(memberRepository.findById(9L)).thenReturn(Optional.empty());

    ApiException failure = assertThrows(ApiException.class, () -> service.bindIm(9L, "im_71", "小明", null));

    assertEquals(404, failure.getStatus());
    assertEquals("MEMBER_NOT_FOUND", failure.getCode());
    verify(memberRepository, never()).updateImBinding(anyLong(), any(), any(), any(), any());
  }

  @SuppressWarnings("unchecked")
  private void assertTokensWrittenSha256Hex(Long memberId) {
    ArgumentCaptor<Collection<MemberNameToken>> captor = ArgumentCaptor.forClass(Collection.class);
    verify(nameTokenRepository, atLeastOnce()).replaceForMember(eq(memberId), captor.capture());
    List<MemberNameToken> rows = List.copyOf(captor.getValue());
    assertFalse(rows.isEmpty());
    rows.forEach(row -> assertEquals(64, row.token().length()));
  }

  /** 真实 insert 会把自增主键写回聚合；mock 里必须显式回填，否则 token 重建会因 id 为空而短路。 */
  private void insertAssigningId(Long id) {
    when(memberRepository.insert(any(Member.class))).thenAnswer(invocation -> {
      Member candidate = invocation.getArgument(0);
      return withId(candidate, id);
    });
  }

  private AuditClient.AuditRecord capturedAudit() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    return captor.getValue();
  }

  private static Member withId(Member member, Long id) {
    return new Member(id, member.tenantId(), member.originStoreId(), member.accountId(),
        member.imAccount(), member.imUsername(), member.imBoundAt(), member.memberNo(),
        member.nameCipher(), member.phoneCipher(), member.phoneDigest(), member.levelId(),
        member.status(), member.joinedAt(), member.expiresAt(), member.version());
  }

  private static Member member(Long id, Long tenantId, String memberNo) {
    return new Member(id, tenantId, null, null, null, null, null, memberNo, null, null, null, null,
        Member.STATUS_ACTIVE, null, null, 0);
  }
}
