package com.gvchat.platform.customer.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.infrastructure.time.TimeRangeParams.TimeRange;
import com.gvchat.platform.customer.application.MemberApplicationService;
import com.gvchat.platform.customer.application.PointApplicationService;
import com.gvchat.platform.customer.application.WalletApplicationService;
import com.gvchat.platform.customer.application.WalletTokenDisplayService;
import com.gvchat.platform.customer.infra.client.TenantWalletTokenClient;
import com.gvchat.platform.customer.infra.persistence.po.CstMemberPo;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 客户列表「无有效 IM 关联（{@code onlyUnlinked}）」的**控制器接线**回归。
 *
 * <p>本类只证明参数被原样交给服务层的既有 {@code onlyUnlinked} 通道（SQL 真过滤由
 * {@code MemberUnlinkedFilterIntegrationTest} 用真 H2 证明）：
 * <ul>
 *   <li>{@code onlyUnlinked=true} 一路传到 {@code MemberApplicationService#list(..., boolean)}；</li>
 *   <li>{@code false}/不传走**既有**的 6 参数服务调用（与加本参数之前完全同一条路径），
 *       既有 7 参数重载也仍然存在且等价于 {@code false}；</li>
 *   <li>与关键字/等级/状态/时间区间同时给出时，其余参数一个都不被改写；</li>
 *   <li>真 HTTP 绑定（standalone MockMvc）：{@code ?onlyUnlinked=true} 绑到新参数上；
 *       HTTP 映射只有一个 {@code list} 重载带 {@code @GetMapping}
 *       （两个都带会让 Spring 启动即 ambiguous mapping）。</li>
 * </ul>
 */
class MemberUnlinkedFilterTest {

    private final MemberApplicationService memberService = mock(MemberApplicationService.class);
    private final PointApplicationService pointService = mock(PointApplicationService.class);
    private final WalletApplicationService walletService = mock(WalletApplicationService.class);
    private final TenantWalletTokenClient client = mock(TenantWalletTokenClient.class);

    private final MemberController controller = new MemberController(memberService, pointService, walletService,
            new WalletTokenDisplayService(client));

    @Test
    void onlyUnlinkedTrueIsPassedDownToTheExistingServiceOverload() {
        when(memberService.list(anyLong(), anyLong(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new Page<>(1, 20, 2L));

        Page<CstMemberPo> result = controller.list(1, 20, null, null, null, null, null, true);

        verify(memberService).list(1L, 20L, null, null, null, TimeRange.none(), true);
        assertEquals(2L, result.getTotal());
    }

    /**
     * {@code onlyUnlinked=false} 与不传该参数必须走**同一条**服务层调用（6 参数口径），
     * 不能因为端点多了这个参数就换掉「不筛」时的服务层入口（既有用例断言的正是这条调用）。
     */
    @Test
    void onlyUnlinkedFalseTakesTheSamePathAsAbsent() {
        when(memberService.list(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new Page<>(1, 20, 7L));

        controller.list(1, 20, null, null, null, null, null, false);
        controller.list(1, 20, null, null, null, null, null);

        verify(memberService, times(2)).list(1L, 20L, null, null, null, TimeRange.none());
    }

    /**
     * 既有 7 参数签名（{@code MemberTimeRangeFilterTest} 的 11 处调用）保持不变：
     * 它等价于 {@code onlyUnlinked=false}，落到服务层的 6 参数重载（内部再传 false）。
     */
    @Test
    void legacySevenArgOverloadStillMeansNoUnlinkedFilter() {
        when(memberService.list(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new Page<>(1, 20, 5L));

        controller.list(1, 20, null, null, null, null, null);

        verify(memberService).list(1L, 20L, null, null, null, TimeRange.none());
    }

    @Test
    void onlyUnlinkedCombinesWithEveryExistingFilterWithoutRewritingThem() {
        when(memberService.list(anyLong(), anyLong(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new Page<>(2, 50, 1L));

        controller.list(2, 50, "张", 3L, "ACTIVE", "2026-09-01", "2026-09-30", true);

        verify(memberService).list(2L, 50L, "张", 3L, "ACTIVE",
                new TimeRange(LocalDateTime.of(2026, 9, 1, 0, 0, 0, 0),
                        LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000)),
                true);
    }

    @Test
    void onlyOneListOverloadCarriesTheHttpMapping() {
        Method[] mapped = Arrays.stream(MemberController.class.getMethods())
                .filter(method -> "list".equals(method.getName()))
                .filter(method -> method.getAnnotation(GetMapping.class) != null)
                .toArray(Method[]::new);

        assertEquals(1, mapped.length,
                "同一路径只能有一个 list 处理器，否则 Spring 启动即 ambiguous mapping: "
                        + Arrays.toString(mapped));
        assertEquals(8, mapped[0].getParameterCount(), "带映射的重载必须包含 onlyUnlinked 参数: " + mapped[0]);
    }

    /**
     * 真 HTTP 参数绑定（standalone MockMvc）：{@code ?onlyUnlinked=true} 必须绑到新重载的那个参数上，
     * 而不是 400/404 或绑错参数。同时证明该路径上只有一个处理器（两个映射会在这里就炸）。
     */
    @Test
    void httpGetBindsOnlyUnlinkedTrue() throws Exception {
        when(memberService.list(anyLong(), anyLong(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new Page<>(1, 20, 0L));

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/business/members").param("onlyUnlinked", "true"))
                .andExpect(status().isOk());

        verify(memberService).list(1L, 20L, null, null, null, TimeRange.none(), true);
    }

    /** 不传 / 显式 false 都按默认值 false 绑定，并走既有的 6 参数服务调用。 */
    @Test
    void httpGetBindsOnlyUnlinkedDefaultFalseToTheLegacyPath() throws Exception {
        when(memberService.list(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new Page<>(1, 20, 0L));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        mockMvc.perform(get("/business/members")).andExpect(status().isOk());
        mockMvc.perform(get("/business/members").param("onlyUnlinked", "false")).andExpect(status().isOk());

        verify(memberService, times(2)).list(1L, 20L, null, null, null, TimeRange.none());
    }
}
