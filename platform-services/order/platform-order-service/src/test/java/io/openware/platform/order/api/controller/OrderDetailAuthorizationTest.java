package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.dto.OrderView;
import io.openware.platform.order.application.DailySerialNumberGenerator;
import io.openware.platform.order.application.KtvServerSessionApplicationService;
import io.openware.platform.order.application.KtvSessionApplicationService;
import io.openware.platform.order.application.OrderCancellationApplicationService;
import io.openware.platform.order.domain.ktv.model.KtvSession;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.domain.order.port.MemberLookupProvider;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.model.Order;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
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

    private final OrderRepository orderMapper = mock(OrderRepository.class);
    private final MemberLookupProvider customerLookupMapper = mock(MemberLookupProvider.class);
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
    void initContext() {
        // 订单详情现在走 OrderRepository 端口（条件收在 OrderQuery 里），不再有 Lambda 列引用，
        // 因此不再需要手工装 TableInfo 缓存（SQL 形状由 OrderRepositoryAdapterTest 钉住）。
        TenantContextHolder.clear();
    }

    /** 商户（持 order.view）：可看本租户订单详情，不按 customer_id 收窄。 */
    @Test
    void merchantCanReadAnyOrderInTenant() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1, List.of("order.view")));
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.of(order(ORDER_ID, 500L)));
        when(ktvSessionService.listByOrderIds(List.of(ORDER_ID))).thenReturn(java.util.Map.of());

        OrderView detail = controller.detail(ORDER_ID);

        assertEquals(ORDER_ID, detail.id());
    }

    /** 消费者读自己的单：允许（customer_id 命中）。 */
    @Test
    void consumerCanReadOwnOrder() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1, List.of()));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(Optional.of(MEMBER_ID));
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.of(order(ORDER_ID, MEMBER_ID)));
        when(ktvSessionService.listByOrderIds(List.of(ORDER_ID))).thenReturn(java.util.Map.of());

        assertEquals(ORDER_ID, controller.detail(ORDER_ID).id());
    }

    /** 消费者读他人的单：403（与「订单不存在」的 404 分开，但文案与列表/账单一致）。 */
    @Test
    void consumerCannotReadOtherMembersOrder() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1, List.of()));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(Optional.of(MEMBER_ID));
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.of(order(ORDER_ID, 999L)));

        ApiException error = assertThrows(ApiException.class, () -> controller.detail(ORDER_ID));

        assertEquals(403, error.getStatus());
        assertEquals("PERMISSION_DENIED", error.getCode());
    }

    /** 既不是商户也不是会员：403，不泄露订单是否存在。 */
    @Test
    void unknownCallerIsRejectedBeforeReadingTheOrder() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1, List.of()));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class, () -> controller.detail(ORDER_ID));

        assertEquals(403, error.getStatus());
        assertEquals("PERMISSION_DENIED", error.getCode());
    }

    /** 订单不存在：404 ORDER_NOT_FOUND（商户可看到真实原因）。 */
    @Test
    void missingOrderReturns404() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1, List.of("order.view")));
        when(orderMapper.findById(999L)).thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class, () -> controller.detail(999L));

        assertEquals(404, error.getStatus());
        assertEquals("ORDER_NOT_FOUND", error.getCode());
    }

    /** 详情必须带上包厢/会话投影：否则 App 拿到订单也显示不出是哪个包厢。 */
    @Test
    void detailCarriesSessionProjection() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1, List.of("order.view")));
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.of(order(ORDER_ID, 500L)));
        KtvSession session = KtvSession
                .reserved(TENANT_ID, 100L, null, ORDER_ID, 1023L, "豪华包 XL02", "XL02")
                .withId(48L)
                .withStatus(KtvSession.STATUS_OPEN);
        when(ktvSessionService.listByOrderIds(List.of(ORDER_ID))).thenReturn(java.util.Map.of(ORDER_ID, session));

        OrderView detail = controller.detail(ORDER_ID);

        assertEquals(48L, detail.sessionId());
        assertEquals("豪华包 XL02", detail.roomName());
        assertEquals("OPEN", detail.sessionStatus());
    }

    /** 订单夹具：领域模型不可变，用规范构造器一次成型（审计列不在模型上）。 */
    private static Order order(Long id, Long customerId) {
        return new Order(id, 100L, null, 100L, "ORD-20260918-0001", null, "KTV", customerId, "SERVING", "CNY",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, null, null, 0);
    }
}
