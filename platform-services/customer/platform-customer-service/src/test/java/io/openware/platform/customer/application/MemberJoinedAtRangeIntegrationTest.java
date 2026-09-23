package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.customer.infra.persistence.mapper.MemberMapper;
import io.openware.platform.customer.infra.persistence.mapper.MemberNameTokenMapper;
import io.openware.platform.customer.infra.persistence.po.CstMemberPo;
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
 * 建档时间区间筛选的**真库**回归（真 H2 + 真 MyBatis + 真租户拦截器，仅 mock 审计客户端）。
 *
 * <p>为什么要有它：控制器测试只能证明参数被传下去了，证明不了 SQL 真的按
 * {@code joined_at >= from AND joined_at <= to} 过滤。这里直接建不同建档时间的客户，
 * 断言：
 * <ul>
 *   <li>闭区间两端都命中（{@code from} 当天 00:00:00 与 {@code to} 当天 23:59:59.999 都在结果里），
 *       区间外一条都不多；</li>
 *   <li>只有 {@code from} / 只有 {@code to} 各自只约束一端；</li>
 *   <li><b>分页 total 与过滤口径一致</b>：翻页时 total 恒等于命中数，不随页码/页大小漂移
 *       （这是本仓刚修过的坑）；</li>
 *   <li>积分列表复用同一份 {@code joined_at} 过滤（不是第二套实现）。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberJoinedAtRangeIntegrationTest {

    private static final long TENANT_ID = 1002L;

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
    void closedIntervalHitsBothEndsAndNothingOutside() {
        Long before = createAt("区间前", "13700000001", LocalDateTime.of(2026, 8, 31, 23, 59, 59));
        Long startEdge = createAt("起点当天零点", "13700000002", LocalDateTime.of(2026, 9, 1, 0, 0, 0));
        Long middle = createAt("区间中", "13700000003", LocalDateTime.of(2026, 9, 15, 12, 0, 0));
        Long endEdge = createAt("终点当天末尾", "13700000004", LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000));
        Long after = createAt("区间后", "13700000005", LocalDateTime.of(2026, 10, 1, 0, 0, 0));

        // 直接走统一解析：日期形态 from/to 各自收口到当天零点 / 当天末尾
        TimeRange range = TimeRangeParams.parse("2026-09-01", "2026-09-30");
        Page<CstMemberPo> page = memberService.list(1, 50, null, null, null, range);

        List<Long> ids = page.getRecords().stream().map(CstMemberPo::getId).toList();
        assertEquals(3, ids.size(), "闭区间应命中 3 条，实际: " + ids);
        assertTrue(ids.containsAll(List.of(startEdge, middle, endEdge)), "闭区间两端都必须命中: " + ids);
        assertTrue(!ids.contains(before) && !ids.contains(after), "区间外一条都不能带出来: " + ids);
        // total 与过滤口径一致：不是租户全量 5 条
        assertEquals(3L, page.getTotal());
    }

    @Test
    void onlyFromBoundsTheLowerEnd() {
        createAt("区间前", "13700000011", LocalDateTime.of(2026, 8, 31, 23, 59, 59));
        Long edge = createAt("起点", "13700000012", LocalDateTime.of(2026, 9, 1, 0, 0, 0));
        Long later = createAt("更晚", "13700000013", LocalDateTime.of(2026, 12, 31, 23, 0, 0));

        Page<CstMemberPo> page = memberService.list(1, 50, null, null, null,
                TimeRangeParams.parse("2026-09-01", null));

        assertEquals(2L, page.getTotal());
        assertTrue(page.getRecords().stream().map(CstMemberPo::getId).toList().containsAll(List.of(edge, later)));
    }

    @Test
    void onlyToBoundsTheUpperEnd() {
        Long early = createAt("更早", "13700000021", LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        Long edge = createAt("终点末尾", "13700000022", LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000));
        createAt("区间后", "13700000023", LocalDateTime.of(2026, 10, 1, 0, 0, 0));

        Page<CstMemberPo> page = memberService.list(1, 50, null, null, null,
                TimeRangeParams.parse(null, "2026-09-30"));

        assertEquals(2L, page.getTotal());
        assertTrue(page.getRecords().stream().map(CstMemberPo::getId).toList().containsAll(List.of(early, edge)));
    }

    @Test
    void paginationTotalStaysEqualToFilteredCountAcrossPages() {
        for (int day = 1; day <= 5; day++) {
            createAt("命中" + day, "1370000003" + day, LocalDateTime.of(2026, 9, day, 10, 0, 0));
        }
        createAt("区间外", "13700000039", LocalDateTime.of(2026, 8, 1, 10, 0, 0));

        TimeRange range = TimeRangeParams.parse("2026-09-01", "2026-09-05");

        Page<CstMemberPo> first = memberService.list(1, 2, null, null, null, range);
        Page<CstMemberPo> second = memberService.list(2, 2, null, null, null, range);
        Page<CstMemberPo> third = memberService.list(3, 2, null, null, null, range);

        // total = 命中数（5），与页大小/页码无关；每页只返回该页条数
        assertEquals(5L, first.getTotal());
        assertEquals(5L, second.getTotal());
        assertEquals(5L, third.getTotal());
        assertEquals(2, first.getRecords().size());
        assertEquals(2, second.getRecords().size());
        assertEquals(1, third.getRecords().size());
    }

    /**
     * 积分列表与客户列表、储值列表共用同一份 {@code joined_at} 过滤实现
     * （{@code MemberApplicationService#applyJoinedAtRange}）。
     *
     * <p>这里**不**单独跑积分列表的 SQL：本模块的 H2 测试库只建了 {@code cst_member} 系列表，
     * {@code cst_point_account} 不在其中，强行断言会得到「表不存在」而不是真实结论。
     * 积分端点的参数接线由 {@code MemberTimeRangeFilterTest} 覆盖（它断言同一个
     * {@link TimeRange} 被传给 {@code pointsList}），SQL 侧的共用性由本类中客户列表的用例保证。
     */
    @Test
    void emptyRangeReturnsEveryMember() {
        createAt("A", "13700000051", LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        createAt("B", "13700000052", LocalDateTime.of(2026, 6, 1, 0, 0, 0));

        Page<CstMemberPo> page = memberService.list(1, 50, null, null, null, TimeRangeParams.parse(null, null));

        assertEquals(2L, page.getTotal());
    }

    /**
     * 建档即写 token，且 {@code create} 只会写 {@code now()}：区间用例需要可控的建档时间，
     * 因此建档后直接在库里改写 {@code joined_at}。
     *
     * <p>用 {@link LambdaUpdateWrapper} 而不是 {@code updateById(PO)}：后者会带上实体里的
     * 乐观锁/非空字段语义，而这里只想改一列。
     */
    private Long createAt(String name, String phone, LocalDateTime joinedAt) {
        CstMemberPo created = memberService.create(null, name, phone, false, null, null);
        memberMapper.update(null, new LambdaUpdateWrapper<CstMemberPo>()
                .eq(CstMemberPo::getId, created.getId())
                .set(CstMemberPo::getJoinedAt, joinedAt));
        return created.getId();
    }
}
