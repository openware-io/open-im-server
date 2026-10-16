package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.customer.domain.account.model.AccountPurgeCount;
import io.openware.platform.customer.domain.member.model.Member;
import io.openware.platform.customer.domain.member.repository.MemberImBindingRepository;
import io.openware.platform.customer.domain.member.repository.MemberImBindingRepository.ImBinding;
import io.openware.platform.customer.domain.member.repository.MemberNameTokenRepository;
import io.openware.platform.customer.domain.member.repository.MemberPurgeReferenceRepository;
import io.openware.platform.customer.domain.member.repository.MemberPurgeReferenceRepository.PurgeBlockers;
import io.openware.platform.customer.domain.member.repository.MemberRepository;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository;
import io.openware.platform.customer.domain.wallet.repository.WalletAccountRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 无 IM 关联客户的清理：两道闸（有 IM 关联 / 有业务引用）与正常删除路径。
 *
 * <p>门店口径：正常客户都从 A380 入口经 IM 授权进来，所以 {@code im_account IS NULL} 就是垃圾数据；
 * 但只要已有业务引用（订单/预约/储值/积分/券）就必须人工判断，绝不静默删。
 *
 * <p>本类只驱动领域端口：删除动作的<b>范围与顺序</b>由各仓储的窄口清理方法保证
 * （{@code purgeEmptyAccountsFor} / {@code purgeAccountsFor} 里"哪些算空账户"的判定有各自的
 * 适配器测试），本类断言的是闸门顺序、删除的发起与审计详情里的计数。
 */
class MemberPurgeApplicationServiceTest {

    private MemberRepository memberRepository;
    private MemberNameTokenRepository nameTokenRepository;
    private MemberPurgeReferenceRepository referenceRepository;
    private MemberImBindingRepository imBindingRepository;
    private PointAccountRepository pointAccountRepository;
    private WalletAccountRepository walletAccountRepository;
    private AuditClient auditClient;
    private MemberPurgeApplicationService service;

    @BeforeEach
    void setUp() {
        memberRepository = mock(MemberRepository.class);
        nameTokenRepository = mock(MemberNameTokenRepository.class);
        referenceRepository = mock(MemberPurgeReferenceRepository.class);
        imBindingRepository = mock(MemberImBindingRepository.class);
        pointAccountRepository = mock(PointAccountRepository.class);
        walletAccountRepository = mock(WalletAccountRepository.class);
        auditClient = mock(AuditClient.class);
        service = new MemberPurgeApplicationService(memberRepository, nameTokenRepository,
                referenceRepository, imBindingRepository, pointAccountRepository,
                walletAccountRepository, auditClient);
    }

    private static Member member(Long id, String imAccount) {
        return new Member(id, 100L, null, null, imAccount, null, null, "M123456", null, null, null,
                null, Member.STATUS_ACTIVE, null, null, 0);
    }

    private static PurgeBlockers noBlockers() {
        return new PurgeBlockers(0L, 0L, 0L, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("客户不存在 → 404，不做任何删除")
    void purgeRejectsMissingMember() {
        when(memberRepository.findById(9L)).thenReturn(Optional.empty());

        ApiException failure = assertThrows(ApiException.class, () -> service.purgeUnlinked(9L));

        assertEquals(404, failure.getStatus());
        assertEquals("MEMBER_NOT_FOUND", failure.getCode());
        verify(memberRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("有 IM 关联 → 409 MEMBER_HAS_IM_BINDING（绝不按垃圾数据清理）")
    void purgeRejectsMemberWithImBinding() {
        when(memberRepository.findById(5L)).thenReturn(Optional.of(member(5L, "im_158")));
        // IM 账号仍然有效：IM 侧用户行存在
        when(imBindingRepository.resolveBindings(List.of(5L)))
                .thenReturn(List.of(new ImBinding(5L, null, 71L, "user600", "小明", false)));

        ApiException failure = assertThrows(ApiException.class, () -> service.purgeUnlinked(5L));

        assertEquals(409, failure.getStatus());
        assertEquals("MEMBER_HAS_IM_BINDING", failure.getCode());
        assertTrue(failure.getMessage().contains("im_158"));
        verify(memberRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("IM 侧账号已不存在 → 允许按垃圾数据清理（失效关联不是有效关联）")
    void purgeAllowsMemberWhoseImAccountIsGone() {
        when(memberRepository.findById(4L)).thenReturn(Optional.of(member(4L, "im_158")));
        when(imBindingRepository.resolveBindings(List.of(4L)))
                .thenReturn(List.of(new ImBinding(4L, null, null, null, null, true)));
        when(referenceRepository.blockersFor(4L)).thenReturn(noBlockers());
        when(nameTokenRepository.deleteByMember(4L)).thenReturn(0);
        when(walletAccountRepository.purgeEmptyAccountsFor(4L)).thenReturn(AccountPurgeCount.none());
        when(pointAccountRepository.purgeAccountsFor(4L)).thenReturn(AccountPurgeCount.none());

        MemberPurgeApplicationService.PurgeResult result = service.purgeUnlinked(4L);

        assertEquals("M123456", result.memberNo());
        verify(memberRepository).deleteById(4L);
        AuditClient.AuditRecord record = capturedAudit();
        assertTrue(record.detailJson().contains("\"imAccountDeleted\":true"));
    }

    /** IM 状态判定失败 → fail-closed：仍然拒绝清理，宁可人工处理也不误删。 */
    @Test
    @DisplayName("IM 状态查询失败 → fail-closed，仍按 MEMBER_HAS_IM_BINDING 拒绝")
    void purgeFailsClosedWhenImLookupBreaks() {
        when(memberRepository.findById(6L)).thenReturn(Optional.of(member(6L, "im_158")));
        when(imBindingRepository.resolveBindings(List.of(6L)))
                .thenThrow(new ApiException(500, "IM_DB_DOWN", "查询失败"));

        ApiException failure = assertThrows(ApiException.class, () -> service.purgeUnlinked(6L));

        assertEquals(409, failure.getStatus());
        assertEquals("MEMBER_HAS_IM_BINDING", failure.getCode());
        verify(memberRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("有业务引用 → 409 MEMBER_HAS_REFERENCES，并把引用类型写进提示")
    void purgeRejectsMemberWithReferences() {
        when(memberRepository.findById(2L)).thenReturn(Optional.of(member(2L, null)));
        when(referenceRepository.blockersFor(2L)).thenReturn(new PurgeBlockers(1L, 2L, 1L, 1L, 3L, 4L));

        ApiException failure = assertThrows(ApiException.class, () -> service.purgeUnlinked(2L));

        assertEquals(409, failure.getStatus());
        assertEquals("MEMBER_HAS_REFERENCES", failure.getCode());
        assertTrue(failure.getMessage().contains("订单 1 条"), failure.getMessage());
        assertTrue(failure.getMessage().contains("预约 2 条"), failure.getMessage());
        assertTrue(failure.getMessage().contains("储值账户 1 条"), failure.getMessage());
        assertTrue(failure.getMessage().contains("积分余额 1 条"), failure.getMessage());
        assertTrue(failure.getMessage().contains("优惠券 3 条"), failure.getMessage());
        assertTrue(failure.getMessage().contains("券核销记录 4 条"), failure.getMessage());
        verify(memberRepository, never()).deleteById(anyLong());
    }

    /** 体检读不到 → fail-closed：按"有引用"拒绝，绝不放行删除。 */
    @Test
    @DisplayName("引用体检失败 → fail-closed，按 MEMBER_HAS_REFERENCES 拒绝")
    void purgeFailsClosedWhenReferenceCheckBreaks() {
        when(memberRepository.findById(3L)).thenReturn(Optional.of(member(3L, null)));
        when(referenceRepository.blockersFor(3L)).thenThrow(new IllegalStateException("体检结果缺失"));

        ApiException failure = assertThrows(ApiException.class, () -> service.purgeUnlinked(3L));

        assertEquals(409, failure.getStatus());
        assertEquals("MEMBER_HAS_REFERENCES", failure.getCode());
        verify(memberRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("无 IM 关联且无引用 → 删除姓名索引/账户/档案，并把五个计数写进审计")
    void purgeDeletesUnlinkedMemberWithoutReferences() {
        when(memberRepository.findById(26L)).thenReturn(Optional.of(member(26L, null)));
        when(referenceRepository.blockersFor(26L)).thenReturn(noBlockers());
        when(nameTokenRepository.deleteByMember(26L)).thenReturn(3);
        when(walletAccountRepository.purgeEmptyAccountsFor(26L)).thenReturn(new AccountPurgeCount(1, 2));
        when(pointAccountRepository.purgeAccountsFor(26L)).thenReturn(new AccountPurgeCount(1, 4));

        MemberPurgeApplicationService.PurgeResult result = service.purgeUnlinked(26L);

        assertEquals("M123456", result.memberNo());
        assertEquals(3, result.nameTokensDeleted());
        assertEquals(1, result.pointAccountsDeleted());
        verify(memberRepository).deleteById(26L);
        verify(walletAccountRepository).purgeEmptyAccountsFor(26L);
        verify(pointAccountRepository).purgeAccountsFor(26L);
        AuditClient.AuditRecord record = capturedAudit();
        assertEquals("member.purge_unlinked", record.action());
        assertEquals("26", record.resourceId());
        assertTrue(record.detailJson().contains("\"nameTokensDeleted\":3"), record.detailJson());
        assertTrue(record.detailJson().contains("\"walletAccountsDeleted\":1"), record.detailJson());
        assertTrue(record.detailJson().contains("\"walletLedgersDeleted\":2"), record.detailJson());
        assertTrue(record.detailJson().contains("\"pointAccountsDeleted\":1"), record.detailJson());
        assertTrue(record.detailJson().contains("\"pointLedgersDeleted\":4"), record.detailJson());
        // 审计详情绝不带姓名/手机号
        assertEquals(false, record.detailJson().contains("cipher"));
    }

    private AuditClient.AuditRecord capturedAudit() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        return captor.getValue();
    }
}
