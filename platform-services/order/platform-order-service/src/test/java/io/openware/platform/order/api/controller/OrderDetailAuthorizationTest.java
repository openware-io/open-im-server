package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
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

/**
 * 订单详情端点（{@code GET /business/orders/{id}}）的授权回归。
 *
 * <p>背景：A380 收银端 App 一直按这个路径取单，但服务端此前**根本没有实现**该路径，
 * 线上实测 404 —— 计时加项页/结算页拿不到订单，只能落到错误态。补齐后必须与列表同一授权口径：
 * 商户（{@code order.view}）可看本租户订单、消费者只能看自己的单、两者都不是 403、
 * 不存在 404，且**不得**用 403/404 的差异泄露他人订单是否存在。
 */
class OrderDetailAuthorizationTest {

    private static final long TENANT_ID = 100L;
    private static final long ACCOUNT_ID = 7L;
    private static final long MEMBER_ID = 77L;
    private static final long ORDER_ID = 61L;

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final CustomerLookupMapper customerLookupMapper = mock(CustomerLookupMapper.class);
    private final KtvSessionApplicationService ktvSessionService = mock(KtvSessionApplicationService.class);
    private final OrderController controller = new OrderController(
            orderMapper,
            ktvSessionService,
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

    @BeforeEach
    void initTableInfo() {
        TableInfo tableInfo = TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderPo.class);
        LambdaUtils.installCache(tableInfo);
    }

    /** 商户（持 order.view）：可看本租户订单详情，不按 customer_id 收窄。 */
    @Test
    void merchantCanReadAnyOrderInTenant() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1, List.of("order.view")));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(ORDER_ID, 500L));
        when(ktvSessionService.listByOrderIds(List.of(ORDER_ID))).thenReturn(java.util.Map.of());

        OrderPo detail = controller.detail(ORDER_ID);

        assertEquals(ORDER_ID, detail.getId());
    }

    /** 消费者读自己的单：允许（customer_id 命中）。 */
    @Test
    void consumerCanReadOwnOrder() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1, List.of()));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(MEMBER_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID));
        when(ktvSessionService.listByOrderIds(List.of(ORDER_ID))).thenReturn(java.util.Map.of());

        assertEquals(ORDER_ID, controller.detail(ORDER_ID).getId());
    }

    /** 消费者读他人的单：403（与「订单不存在」的 404 分开，但文案与列表/账单一致）。 */
    @Test
    void consumerCannotReadOtherMembersOrder() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1, List.of()));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(MEMBER_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(ORDER_ID, 999L));

        ApiException error = assertThrows(ApiException.class, () -> controller.detail(ORDER_ID));

        assertEquals(403, error.getStatus());
        assertEquals("PERMISSION_DENIED", error.getCode());
    }

    /** 既不是商户也不是会员：403，不泄露订单是否存在。 */
    @Test
    void unknownCallerIsRejectedBeforeReadingTheOrder() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1, List.of()));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> controller.detail(ORDER_ID));

        assertEquals(403, error.getStatus());
        assertEquals("PERMISSION_DENIED", error.getCode());
    }

    /** 订单不存在：404 ORDER_NOT_FOUND（商户可看到真实原因）。 */
    @Test
    void missingOrderReturns404() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1, List.of("order.view")));
        when(orderMapper.selectById(999L)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> controller.detail(999L));

        assertEquals(404, error.getStatus());
        assertEquals("ORDER_NOT_FOUND", error.getCode());
    }

    /** 详情必须带上包厢/会话投影：否则 App 拿到订单也显示不出是哪个包厢。 */
    @Test
    void detailCarriesSessionProjection() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1, List.of("order.view")));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(ORDER_ID, 500L));
        io.openware.platform.order.infra.persistence.po.KtvSessionPo session =
                new io.openware.platform.order.infra.persistence.po.KtvSessionPo();
        session.setId(48L);
        session.setOrderId(ORDER_ID);
        session.setRoomResourceId(1023L);
        session.setRoomNameSnapshot("豪华包 XL02");
        session.setRoomCodeSnapshot("XL02");
        session.setStatus("OPEN");
        when(ktvSessionService.listByOrderIds(List.of(ORDER_ID))).thenReturn(java.util.Map.of(ORDER_ID, session));

        OrderPo detail = controller.detail(ORDER_ID);

        assertEquals(48L, detail.getSessionId());
        assertEquals("豪华包 XL02", detail.getRoomName());
        assertEquals("OPEN", detail.getSessionStatus());
    }

    private static OrderPo order(Long id, Long customerId) {
        OrderPo po = new OrderPo();
        po.setId(id);
        po.setCustomerId(customerId);
        po.setStoreId(100L);
        return po;
    }
}
