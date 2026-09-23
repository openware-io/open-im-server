package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.customer.infra.persistence.mapper.MemberMapper;
import io.openware.platform.customer.infra.persistence.mapper.MemberNameTokenMapper;
import io.openware.platform.customer.infra.persistence.mapper.PointAccountMapper;
import io.openware.platform.customer.infra.persistence.po.CstMemberPo;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 无 IM 关联客户的清理：两道闸（有 IM 关联 / 有业务引用）与正常删除路径。
 *
 * <p>门店口径：正常客户都从 A380 入口经 IM 授权进来，所以 {@code im_account IS NULL} 就是垃圾数据；
 * 但只要已有业务引用（订单/预约/储值/积分/券）就必须人工判断，绝不静默删。
 */
class MemberPurgeApplicationServiceTest {

    private MemberMapper memberMapper;
    private MemberNameTokenMapper nameTokenMapper;
    private PointAccountMapper pointAccountMapper;
    private AuditClient auditClient;
    private MemberPurgeApplicationService service;

    @BeforeEach
    void setUp() {
        memberMapper = mock(MemberMapper.class);
        nameTokenMapper = mock(MemberNameTokenMapper.class);
        pointAccountMapper = mock(PointAccountMapper.class);
        auditClient = mock(AuditClient.class);
        service = new MemberPurgeApplicationService(memberMapper, nameTokenMapper, pointAccountMapper, auditClient);
    }

    private static CstMemberPo member(Long id, String imAccount) {
        CstMemberPo po = new CstMemberPo();
        po.setId(id);
        po.setMemberNo("M123456");
        po.setImAccount(imAccount);
        return po;
    }

    private static Map<String, Object> noBlockers() {
        Map<String, Object> map = new HashMap<>();
        for (String key : new String[] { "orders_count", "reservations_count", "wallets_count",
                "points_count", "coupons_issued_count", "coupons_redeemed_count" }) {
            map.put(key, 0L);
        }
        return map;
    }

    @Test
    @DisplayName("客户不存在 → 404，不做任何删除")
    void purgeRejectsMissingMember() {
        when(memberMapper.selectById(9L)).thenReturn(null);

        ApiException failure = assertThrows(ApiException.class, () -> service.purgeUnlinked(9L));

        assertEquals(404, failure.getStatus());
        assertEquals("MEMBER_NOT_FOUND", failure.getCode());
        verify(memberMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("有 IM 关联 → 409 MEMBER_HAS_IM_BINDING（绝不按垃圾数据清理）")
    void purgeRejectsMemberWithImBinding() {
        when(memberMapper.selectById(5L)).thenReturn(member(5L, "im_158"));

        ApiException failure = assertThrows(ApiException.class, () -> service.purgeUnlinked(5L));

        assertEquals(409, failure.getStatus());
        assertEquals("MEMBER_HAS_IM_BINDING", failure.getCode());
        verify(memberMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("有业务引用 → 409 MEMBER_HAS_REFERENCES，并把引用类型写进提示")
    void purgeRejectsMemberWithReferences() {
        when(memberMapper.selectById(2L)).thenReturn(member(2L, null));
        Map<String, Object> blockers = noBlockers();
        blockers.put("orders_count", 1L);
        blockers.put("reservations_count", 2L);
        when(memberMapper.selectPurgeBlockers(2L)).thenReturn(blockers);

        ApiException failure = assertThrows(ApiException.class, () -> service.purgeUnlinked(2L));

        assertEquals(409, failure.getStatus());
        assertEquals("MEMBER_HAS_REFERENCES", failure.getCode());
        assertEquals(true, failure.getMessage().contains("订单 1 条"));
        assertEquals(true, failure.getMessage().contains("预约 2 条"));
        verify(memberMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("无 IM 关联且无引用 → 删除姓名索引/零额积分账户/档案本身，并写审计")
    void purgeDeletesUnlinkedMemberWithoutReferences() {
        when(memberMapper.selectById(26L)).thenReturn(member(26L, null));
        when(memberMapper.selectPurgeBlockers(26L)).thenReturn(noBlockers());
        when(nameTokenMapper.delete(any())).thenReturn(3);
        when(pointAccountMapper.delete(any())).thenReturn(1);

        MemberPurgeApplicationService.PurgeResult result = service.purgeUnlinked(26L);

        assertEquals("M123456", result.memberNo());
        assertEquals(3, result.nameTokensDeleted());
        assertEquals(1, result.pointAccountsDeleted());
        verify(memberMapper).deleteById(26L);
        verify(auditClient).recordAsync(any(AuditClient.AuditRecord.class));
    }
}
