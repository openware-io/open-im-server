package com.gvchat.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.infrastructure.time.TimeRangeParams;
import com.gvchat.infrastructure.time.TimeRangeParams.TimeRange;
import com.gvchat.platform.customer.infra.persistence.mapper.MemberMapper;
import com.gvchat.platform.customer.infra.persistence.mapper.MemberNameTokenMapper;
import com.gvchat.platform.customer.infra.persistence.po.CstMemberPo;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 「无有效 IM 关联（{@code onlyUnlinked}）」筛选的**真库**回归（真 H2 + 真 MyBatis + 真租户拦截器，
 * 仅 mock 审计客户端）。
 *
 * <p>为什么要有它：控制器用例只能证明参数被传下去了，证明不了 SQL 真的按
 * {@code cst_member.im_account IS NULL} 过滤。这里建不同 IM 关联形态的客户，断言：
 * <ul>
 *   <li>{@code onlyUnlinked=true} 只返回 {@code im_account IS NULL} 的行——
 *       注意 {@code account_id} 有值（账号存在但**没有 IM 身份**）也算无关联，这正是垃圾档案的口径；</li>
 *   <li>{@code im_account} **有值**的行一条都不返回，**包括 IM 侧账号已删除**（墓碑）的那种：
 *       它属于「关联已失效」而不是「从未关联」，由列表行的展示字段 {@code imAccountDeleted} 单独标记，
 *       不并入本参数（不要重载本参数去折叠它）；</li>
 *   <li>{@code onlyUnlinked=false} 与不传（旧的 6 参数重载）都返回全部；</li>
 *   <li>与关键字（盲索引）/等级/状态/建档时间区间同时生效，且**分页 total 与过滤口径一致**。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberUnlinkedFilterIntegrationTest {

    private static final long TENANT_ID = 1003L;

    @MockitoBean
    private AuditClient auditClient;

    @Autowired
    private MemberApplicationService memberService;

    @Autowired
    private MemberMapper memberMapper;

    @Autowired
    private MemberNameTokenMapper nameTokenMapper;

    @BeforeEach
    void setUpTenantAndCleanData() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, 2001L, 0L, 0, List.of("member.pii.view")));
        nameTokenMapper.delete(new QueryWrapper<>());
        memberMapper.delete(new QueryWrapper<>());
    }

    @AfterEach
    void tearDownTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void onlyUnlinkedTrueReturnsOnlyRowsWithoutImAccount() {
        Long withoutAccount = create("待认领客户", "13700000101", null, LocalDateTime.of(2026, 9, 1, 10, 0, 0));
        // 账号存在（account_id 有值）但没有 IM 身份：同样是「无有效 IM 关联」
        Long accountWithoutIm = create("有账号无IM", "13700000102", null, LocalDateTime.of(2026, 9, 2, 10, 0, 0));
        setAccountId(accountWithoutIm, 9001L);
        Long linked = create("已关联IM", "13700000103", "im_2001", LocalDateTime.of(2026, 9, 3, 10, 0, 0));
        // IM 后台删除用户后的残档：im_account 仍有值（不是 NULL），因此**不属于** onlyUnlinked
        Long linkedButImDeleted = create("IM账号已删除", "13700000104", "im_2002", LocalDateTime.of(2026, 9, 4, 10, 0, 0));

        Page<CstMemberPo> page = memberService.list(1, 50, null, null, null, TimeRange.none(), true);

        List<Long> ids = ids(page);
        assertEquals(2L, page.getTotal(), "只有 im_account IS NULL 的 2 条命中，实际: " + ids);
        assertEquals(2, ids.size(), "实际: " + ids);
        assertTrue(ids.containsAll(List.of(withoutAccount, accountWithoutIm)), "im_account IS NULL 必须命中: " + ids);
        assertFalse(ids.contains(linked), "有 IM 关联的不能命中: " + ids);
        assertFalse(ids.contains(linkedButImDeleted),
                "IM 侧账号已删除（im_account 非空）不是「从未关联」，不得并入 onlyUnlinked: " + ids);
    }

    @Test
    void onlyUnlinkedFalseAndAbsentBothReturnEverything() {
        Long unlinked = create("无关联", "13700000111", null, LocalDateTime.of(2026, 9, 1, 10, 0, 0));
        Long linked = create("已关联", "13700000112", "im_2011", LocalDateTime.of(2026, 9, 2, 10, 0, 0));

        Page<CstMemberPo> explicitFalse = memberService.list(1, 50, null, null, null, TimeRange.none(), false);
        // 旧签名（不传 onlyUnlinked）等价于 false：两条路径结果必须完全一致
        Page<CstMemberPo> absent = memberService.list(1, 50, null, null, null, TimeRange.none());

        assertEquals(2L, explicitFalse.getTotal());
        assertEquals(2L, absent.getTotal());
        assertEquals(ids(explicitFalse), ids(absent), "缺省即 false，两条路径口径必须一致");
        assertTrue(ids(absent).containsAll(List.of(unlinked, linked)));
    }

    @Test
    void onlyUnlinkedCombinesWithKeywordLevelStatusAndTimeRange() {
        Long hit = create("张垃圾甲", "13700000121", null, LocalDateTime.of(2026, 9, 10, 10, 0, 0));
        setLevelAndStatus(hit, 3L, "ACTIVE");

        Long statusMiss = create("张垃圾乙", "13700000122", null, LocalDateTime.of(2026, 9, 10, 10, 0, 0));
        setLevelAndStatus(statusMiss, 3L, "FROZEN");

        Long levelMiss = create("张垃圾丙", "13700000123", null, LocalDateTime.of(2026, 9, 10, 10, 0, 0));
        setLevelAndStatus(levelMiss, 4L, "ACTIVE");

        Long timeMiss = create("张垃圾丁", "13700000124", null, LocalDateTime.of(2026, 8, 1, 10, 0, 0));
        setLevelAndStatus(timeMiss, 3L, "ACTIVE");

        // 同样满足关键字/等级/状态/时间区间，但 im_account 有值 → 被 onlyUnlinked 排除
        Long linkedMiss = create("张已关联", "13700000125", "im_2021", LocalDateTime.of(2026, 9, 10, 10, 0, 0));
        setLevelAndStatus(linkedMiss, 3L, "ACTIVE");

        // 时间口径走统一解析，与控制器端点同一份实现（日期形态收口到闭区间）
        TimeRange september = TimeRangeParams.parse("2026-09-01", "2026-09-30");

        Page<CstMemberPo> onlyUnlinked = memberService.list(1, 50, "张", 3L, "ACTIVE", september, true);

        List<Long> ids = ids(onlyUnlinked);
        assertEquals(1L, onlyUnlinked.getTotal(), "四个条件 + onlyUnlinked 只剩 1 条命中，实际: " + ids);
        assertEquals(List.of(hit), ids);

        // 只把 onlyUnlinked 翻成 false：同条件多出「已关联」那条，其余筛选没有被改写
        Page<CstMemberPo> withoutUnlinkedFilter = memberService.list(1, 50, "张", 3L, "ACTIVE", september, false);
        assertEquals(2L, withoutUnlinkedFilter.getTotal());
        assertTrue(ids(withoutUnlinkedFilter).containsAll(List.of(hit, linkedMiss)));
    }

    @Test
    void onlyUnlinkedKeepsPagingAndTotalConsistent() {
        for (int i = 0; i < 3; i++) {
            create("无关联" + i, "1370000013" + i, null, LocalDateTime.of(2026, 9, 1 + i, 10, 0, 0));
        }
        Long linkedA = create("已关联A", "13700000141", "im_2031", LocalDateTime.of(2026, 9, 5, 10, 0, 0));
        Long linkedB = create("已关联B", "13700000142", "im_2032", LocalDateTime.of(2026, 9, 6, 10, 0, 0));

        Page<CstMemberPo> first = memberService.list(1, 2, null, null, null, TimeRange.none(), true);
        Page<CstMemberPo> second = memberService.list(2, 2, null, null, null, TimeRange.none(), true);

        // total = 命中数（3），与页大小/页码无关；每页只返回该页条数
        assertEquals(3L, first.getTotal());
        assertEquals(3L, second.getTotal());
        assertEquals(2, first.getRecords().size());
        assertEquals(1, second.getRecords().size());
        assertFalse(ids(first).contains(linkedA) || ids(second).contains(linkedB),
                "分页也不得把有 IM 关联的行带出来");
    }

    // ------------------------------------------------------------ helpers

    /**
     * 建档（{@code accountId} 传 null，避免在只建了 cst_ 系列表的 H2 测试库上查询 idt_login_identity），
     * 然后直接改写建档时间——{@code create} 只会写 {@code now()}。
     */
    private Long create(String name, String phone, String imAccount, LocalDateTime joinedAt) {
        CstMemberPo created = memberService.create(null, name, phone, false, imAccount,
                imAccount == null ? null : "user-" + imAccount);
        assertNotNull(created.getId(), "建档必须落库");
        memberMapper.update(null, new LambdaUpdateWrapper<CstMemberPo>()
                .eq(CstMemberPo::getId, created.getId())
                .set(CstMemberPo::getJoinedAt, joinedAt));
        return created.getId();
    }

    private void setAccountId(Long id, Long accountId) {
        memberMapper.update(null, new LambdaUpdateWrapper<CstMemberPo>()
                .eq(CstMemberPo::getId, id)
                .set(CstMemberPo::getAccountId, accountId));
    }

    private void setLevelAndStatus(Long id, Long levelId, String status) {
        memberMapper.update(null, new LambdaUpdateWrapper<CstMemberPo>()
                .eq(CstMemberPo::getId, id)
                .set(CstMemberPo::getLevelId, levelId)
                .set(CstMemberPo::getStatus, status));
    }

    private static List<Long> ids(Page<CstMemberPo> page) {
        return page.getRecords().stream().map(CstMemberPo::getId).toList();
    }
}
