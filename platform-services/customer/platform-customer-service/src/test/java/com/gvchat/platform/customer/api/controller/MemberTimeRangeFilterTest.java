package com.gvchat.platform.customer.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.infrastructure.time.TimeRangeParams;
import com.gvchat.infrastructure.time.TimeRangeParams.TimeRange;
import com.gvchat.platform.customer.application.MemberApplicationService;
import com.gvchat.platform.customer.application.PointApplicationService;
import com.gvchat.platform.customer.application.WalletApplicationService;
import com.gvchat.platform.customer.application.WalletTokenDisplayService;
import com.gvchat.platform.customer.infra.client.TenantWalletTokenClient;
import com.gvchat.platform.customer.infra.persistence.po.CstMemberPo;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 客户域三个列表端点的**时间区间**接线回归（业务时间列 = 建档时间 {@code joined_at}）。
 *
 * <p>覆盖契约（与 {@link TimeRangeParams} 同一口径）：
 * <ul>
 *   <li>三个端点（{@code /business/members}、{@code /business/members/points}、
 *       {@code /business/members/wallets}）都接受 {@code from}/{@code to}，
 *       并把它交给同一个 {@link TimeRange}；</li>
 *   <li>{@code yyyy-MM-dd} 的 {@code from} 收口到当天 {@code 00:00:00.000}、
 *       {@code to} 收口到当天 {@code 23:59:59.999}（闭区间，且不溢出到次日）；</li>
 *   <li>只有一端时另一端不筛；两端都空 = 不筛；</li>
 *   <li>{@code from > to} → 400 {@code TIME_RANGE_INVALID}，且**不落到服务层**（没有查询被发出）；</li>
 *   <li>分页与 total 口径一致：控制器不改写服务层返回的 total。</li>
 * </ul>
 */
class MemberTimeRangeFilterTest {

    /** 闭区间收口的期望值：{@code to = 当天 23:59:59.999}。 */
    private static final LocalDateTime CLOSED_END = LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000);

    private static final long TENANT_ID = 100L;

    private final MemberApplicationService memberService = mock(MemberApplicationService.class);
    private final PointApplicationService pointService = mock(PointApplicationService.class);
    private final WalletApplicationService walletService = mock(WalletApplicationService.class);
    private final TenantWalletTokenClient client = mock(TenantWalletTokenClient.class);

    private MemberController controller;

    @BeforeEach
    void setUp() {
        controller = new MemberController(memberService, pointService, walletService,
                new WalletTokenDisplayService(client));
        // 储值列表会先按上下文租户解析代币展示配置（比例用于代币数量换算），缺上下文/缺配置会 NPE。
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, 2001L, 0L, 0, java.util.List.of()));
        when(client.resolve(TENANT_ID)).thenReturn(new TenantWalletTokenClient.WalletTokenConfig("A380币", 100L));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    // ------------------------------------------------------------ 客户列表

    @Test
    void membersListPassesClosedDayRangeDownToService() {
        when(memberService.list(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new Page<>(1, 20, 3L));

        Page<CstMemberPo> result = controller.list(1, 20, "张", 2L, "ACTIVE", "2026-09-01", "2026-09-30");

        // 既有筛选参数与分页一个都没被改写
        verify(memberService).list(1L, 20L, "张", 2L, "ACTIVE",
                new TimeRange(LocalDateTime.of(2026, 9, 1, 0, 0, 0, 0), CLOSED_END));
        assertEquals(3L, result.getTotal());
    }

    @Test
    void membersListClosesDateBoundsWithoutOverflowingToNextDay() {
        when(memberService.list(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new Page<>(1, 20, 0L));

        controller.list(1, 20, null, null, null, "2026-09-01", "2026-09-30");
        TimeRange range = capturedListRange();

        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0, 0, 0), range.fromInclusive());
        assertEquals(CLOSED_END, range.toInclusive());
        // 收口值必须仍在当天：用 LocalTime.MAX 会被 MySQL DATETIME(3) 进位到次日 00:00:00
        assertEquals(30, range.toInclusive().getDayOfMonth());
        assertEquals(23, range.toInclusive().getHour());
    }

    @Test
    void membersListAcceptsExplicitTimeAndFromOnly() {
        when(memberService.list(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new Page<>(1, 20, 0L));

        controller.list(1, 20, null, null, null, "2026-09-01T08:30:00", null);
        TimeRange fromOnly = capturedListRange();

        // 显式时刻按字面值使用，不再收口到整天
        assertEquals(LocalDateTime.of(2026, 9, 1, 8, 30, 0), fromOnly.fromInclusive());
        assertFalse(fromOnly.hasTo());
        assertNull(fromOnly.toInclusive());
    }

    @Test
    void membersListAcceptsToOnly() {
        when(memberService.list(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new Page<>(1, 20, 0L));

        controller.list(1, 20, null, null, null, null, "2026-09-30T20:15:30");
        TimeRange toOnly = capturedListRange();

        assertFalse(toOnly.hasFrom());
        assertEquals(LocalDateTime.of(2026, 9, 30, 20, 15, 30), toOnly.toInclusive());
    }

    @Test
    void membersListWithoutTimeParamsMeansNoFilter() {
        when(memberService.list(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new Page<>(1, 20, 0L));

        controller.list(1, 20, null, null, null, null, null);

        assertTrue(capturedListRange().isEmpty());
    }

    @Test
    void membersListTreatsBlankTimeParamsAsNoFilter() {
        when(memberService.list(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new Page<>(1, 20, 0L));

        // 空串也按「没筛」处理（后端不会把它当非法值，也不会拼出空串条件）
        controller.list(1, 20, null, null, null, "", "  ");

        assertTrue(capturedListRange().isEmpty());
    }

    @Test
    void membersListRejectsInvertedRangeWith400BeforeTouchingService() {
        ApiException failure = assertThrows(ApiException.class,
                () -> controller.list(1, 20, null, null, null, "2026-09-30", "2026-09-01"));

        assertEquals(400, failure.getStatus());
        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
        assertEquals(TimeRangeParams.MESSAGE_TIME_RANGE_INVALID, failure.getMessage());
        // 非法区间不得发出任何查询
        verify(memberService, never()).list(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void membersListRejectsMalformedTimeWith400() {
        ApiException failure = assertThrows(ApiException.class,
                () -> controller.list(1, 20, null, null, null, "2026/09/01", null));

        assertEquals(400, failure.getStatus());
        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
    }

    // ------------------------------------------------------------ 积分列表

    @Test
    void pointsListPassesClosedDayRangeDownToService() {
        when(memberService.pointsList(anyLong(), anyLong(), any(), any()))
                .thenReturn(new Page<>(2, 50, 0));

        controller.pointsList(2, 50, "李", "2026-09-01", "2026-09-30");

        verify(memberService).pointsList(2L, 50L, "李",
                new TimeRange(LocalDateTime.of(2026, 9, 1, 0, 0, 0, 0), CLOSED_END));
    }

    @Test
    void pointsListRejectsInvertedRangeWith400() {
        ApiException failure = assertThrows(ApiException.class,
                () -> controller.pointsList(1, 20, null, "2026-09-30", "2026-09-01"));

        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
        verify(memberService, never()).pointsList(anyLong(), anyLong(), any(), any());
    }

    // ------------------------------------------------------------ 储值列表

    /**
     * 储值列表的时间筛选走的是**同一个** {@code memberService.list}（客户行分页），
     * 因此过滤条件、分页与 total 口径天然与客户列表一致 —— 不存在「第二个实现」。
     */
    @Test
    void walletsListReusesMemberListTimeFilter() {
        when(memberService.list(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new Page<>(1, 20, 1L));

        Page<MemberController.MemberWalletRow> rows = controller.wallets(1, 20, "王", "2026-09-01", "2026-09-30");

        verify(memberService).list(1L, 20L, "王", null, null,
                new TimeRange(LocalDateTime.of(2026, 9, 1, 0, 0, 0, 0), CLOSED_END));
        // total 来自过滤后的客户分页（1 条），不是租户全量
        assertEquals(1L, rows.getTotal());
    }

    @Test
    void walletsListRejectsInvertedRangeWith400() {
        ApiException failure = assertThrows(ApiException.class,
                () -> controller.wallets(1, 20, null, "2026-09-30", "2026-09-01"));

        assertEquals(400, failure.getStatus());
        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
        verify(memberService, never()).list(anyLong(), anyLong(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------ helpers

    private TimeRange capturedListRange() {
        ArgumentCaptor<TimeRange> range = ArgumentCaptor.forClass(TimeRange.class);
        verify(memberService).list(anyLong(), anyLong(), any(), any(), any(), range.capture());
        return range.getValue();
    }
}
