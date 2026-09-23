package io.openware.platform.order.api.controller;

import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.ktv.model.KtvBillingUnit;
import io.openware.platform.order.domain.ktv.model.KtvPricingPlan;
import io.openware.platform.order.domain.ktv.model.KtvRoundingDirection;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.infra.client.RoomTypeCatalogClient;
import io.openware.platform.order.infra.client.RoomTypeCatalogClient.RoomTypeView;
import io.openware.platform.order.infra.config.DefaultKtvPricingPlanProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 预约页按房型报价（docs/renovation/KTV_RESERVATION_ROOM_TYPE.md §3「房型报价」）：
 * {@code GET /business/ktv/pricing?storeId=&roomTypeId=} 必须返回该房型的
 * 「房型单价 + 服务单价 = 合计」，预约页直接取合计口径；房型读不到时降级为门店级单价，不阻断页面。
 */
class KtvPricingRoomTypeTest {

    private static final Long TENANT_ID = 1L;
    private static final Long STORE_ID = 100L;
    private static final Long ROOM_TYPE_ID = 55L;

    private DefaultKtvPricingPlanProvider pricingPlanProvider;
    private KtvPricingController controller;

    @BeforeEach
    void setUp() {
        pricingPlanProvider = mock(DefaultKtvPricingPlanProvider.class);
        RoomTypeCatalogClient roomTypeCatalogClient = mock(RoomTypeCatalogClient.class);
        controller = new KtvPricingController(pricingPlanProvider, mock(ResourceStateClient.class), roomTypeCatalogClient);
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 1L, 1, List.of("reservation.view")));

        when(roomTypeCatalogClient.roomType(STORE_ID, ROOM_TYPE_ID)).thenReturn(Optional.of(
                new RoomTypeView(ROOM_TYPE_ID, "VIP", "VIP 大包", 12, 20000L, 6000L, "ACTIVE")));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /** 按 roomTypeId 报价：房型单价 200 元 + 服务 60 元 = 合计 260 元/小时，并回显 appliedRoomTypeId。 */
    @Test
    void pricingByRoomTypeReturnsCombinedUnitPrice() {
        when(pricingPlanProvider.effectivePlanForRoomType(TENANT_ID, STORE_ID, "VIP", "VIP 大包", 20000L, 6000L))
                .thenReturn(storeLevelPlan().forRoomType("VIP", "VIP 大包", 20000L, 6000L));

        KtvPricingController.PricingView view = controller.current(null, null, null, ROOM_TYPE_ID);

        assertEquals(20000L, view.roomUnitPrice(), "房型单价");
        assertEquals(6000L, view.serverUnitPrice(), "服务单价");
        assertEquals(26000L, view.combinedUnitPrice(), "预约页取合计口径 = 房型 + 服务");
        assertEquals(ROOM_TYPE_ID, view.appliedRoomTypeId());
        assertEquals("VIP", view.appliedRoomTypeCode());
        assertTrue(view.roomTypePriceApplied());
        assertTrue(view.displayText().contains("房型「VIP 大包」"), view.displayText());
    }

    /** 房型字典读不到（已删除/资源域不可达）：降级为门店级单价，不阻断预约页展示。 */
    @Test
    void pricingDegradesToStoreLevelWhenRoomTypeUnknown() {
        RoomTypeCatalogClient catalog = mock(RoomTypeCatalogClient.class);
        when(catalog.roomType(any(), any())).thenReturn(Optional.empty());
        KtvPricingController degrading = new KtvPricingController(pricingPlanProvider,
                mock(ResourceStateClient.class), catalog);
        when(pricingPlanProvider.effectivePlanForRoomType(TENANT_ID, STORE_ID, null, null, null, null))
                .thenReturn(storeLevelPlan());

        KtvPricingController.PricingView view = degrading.current(null, null, null, ROOM_TYPE_ID);

        assertEquals(3000L, view.roomUnitPrice());
        assertEquals(8000L, view.combinedUnitPrice());
        assertNull(view.appliedRoomTypeId());
        assertNull(view.appliedRoomTypeCode());
    }

    private static KtvPricingPlan storeLevelPlan() {
        return new KtvPricingPlan(KtvBillingUnit.HOUR, 3000L, 120, 0, new BigDecimal("1.5"), 30,
                KtvRoundingDirection.CONSUMER_FAVOR, 2500L);
    }
}
