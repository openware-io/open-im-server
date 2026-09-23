package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.LambdaUtils;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.DailySerialNumberGenerator;
import io.openware.platform.order.application.KtvServerSessionApplicationService;
import io.openware.platform.order.application.KtvSessionApplicationService;
import io.openware.platform.order.application.OrderCancellationApplicationService;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.infra.persistence.mapper.CustomerLookupMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 订单列表授权回归（2026-09-19）：旧实现 `GET /business/orders` 既没有权限码也没有归属收窄，
 * 任意登录的消费者会话都能拉到本租户**全部订单**（范围比已修的账单端点更大）。
 *
 * <p>修后口径：持 {@code order.view} → 行为不变（不加 customer_id 条件）；
 * 消费者会话 → 按 {@code ord_order.customer_id} 收窄到本人；
 * 解析不出会员档案 → 403 {@code PERMISSION_DENIED}（与账单端点同文案）；
 * 无签名上下文（内部调用/单测装配）→ 保持既有行为。
 */
class OrderListAuthorizationTest {

    private static final long TENANT_ID = 100L;
    private static final long ACCOUNT_ID = 7L;
    private static final long MEMBER_ID = 77L;

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final CustomerLookupMapper customerLookupMapper = mock(CustomerLookupMapper.class);
    private final OrderController controller = new OrderController(
            orderMapper,
            mock(KtvSessionApplicationService.class),
            mock(KtvServerSessionApplicationService.class),
            mock(ResourceStateClient.class),
            AuditClient.disabled(),
            mock(OrderCancellationApplicationService.class),
            mock(DailySerialNumberGenerator.class),
            customerLookupMapper);

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /**
     * 断言下发的 SQL 需要把 {@code OrderPo::getCreatedAt/getCustomerId} 解析成列名，而 lambda 缓存来自
     * MyBatis-Plus 的 {@code TableInfo}：单元测试没有 MyBatis 启动过程，必须手工初始化
     * （与 {@link TimeRangeListFilterTest} 同款做法）。
     */
    @BeforeEach
    void initTableInfo() {
        TableInfo tableInfo = TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderPo.class);
        LambdaUtils.installCache(tableInfo);
    }

    /** B 端（持 order.view）：不查归属、不加条件，行为与改造前完全一致。 */
    @Test
    void merchantSessionListsWholeTenantWithoutCustomerScope() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1,
                List.of("order.view")));
        when(orderMapper.selectList(any())).thenReturn(List.of());

        controller.list(null, null);

        String sql = capturedQuerySql();
        assertFalse(sql.contains("customer_id"), "商户列表不得被收窄到某个客户: " + sql);
        verify(customerLookupMapper, never()).findMemberId(anyLong(), anyLong());
    }

    /** 消费者会话：按 customer_id 收窄到本人（C 端会话不再能看到同租户他人订单）。 */
    @Test
    void consumerSessionIsScopedToOwnCustomerId() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1,
                List.of("reservation.view")));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(MEMBER_ID);
        when(orderMapper.selectList(any())).thenReturn(List.of());

        controller.list(null, null);

        String sql = capturedQuerySql();
        assertTrue(sql.contains("customer_id"), "消费者列表必须带 customer_id 条件: " + sql);
        assertTrue(capturedQueryParamValues().contains(MEMBER_ID),
                "customer_id 取值必须是签名上下文对应的会员: " + capturedQueryParamValues());
    }

    /** 时间区间与归属收窄共存：两个条件都要在（避免修一处漏一处）。 */
    @Test
    void consumerScopeKeepsTimeRangeFilters() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1,
                List.of("reservation.view")));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(MEMBER_ID);
        when(orderMapper.selectList(any())).thenReturn(List.of());

        controller.list("2026-09-01", "2026-09-30");

        String sql = capturedQuerySql();
        assertTrue(sql.contains("customer_id"), sql);
        assertTrue(sql.contains("created_at >="), sql);
        assertTrue(sql.contains("created_at <="), sql);
    }

    /** 既无商户权限也解析不出会员：403 PERMISSION_DENIED，且根本不查订单表。 */
    @Test
    void consumerWithoutMemberProfileIsRejectedBeforeQuerying() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1,
                List.of("reservation.view")));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class, () -> controller.list(null, null));

        assertEquals(403, ex.getStatus());
        assertEquals("PERMISSION_DENIED", ex.getCode());
        verify(orderMapper, never()).selectList(any());
    }

    /** 无签名上下文（服务间内部调用/单测装配）：保持既有行为，不额外收窄也不拦。 */
    @Test
    void missingContextKeepsLegacyBehaviour() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        controller.list(null, null);

        assertFalse(capturedQuerySql().contains("customer_id"));
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<OrderPo> capturedQuery() {
        ArgumentCaptor<LambdaQueryWrapper<OrderPo>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper).selectList(captor.capture());
        return captor.getValue();
    }

    private String capturedQuerySql() {
        return capturedQuery().getSqlSegment();
    }

    private java.util.Collection<Object> capturedQueryParamValues() {
        return capturedQuery().getParamNameValuePairs().values();
    }
}
