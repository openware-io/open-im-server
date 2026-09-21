package com.gvchat.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.application.InventoryApplicationService;
import com.gvchat.platform.order.application.KtvSessionApplicationService;
import com.gvchat.platform.order.application.OrderAmountApplicationService;
import com.gvchat.platform.order.application.PendingApprovalApplicationService;
import com.gvchat.platform.order.application.SettlementApplicationService;
import com.gvchat.platform.order.handler.GlobalExceptionHandler;
import com.gvchat.platform.order.infra.cache.PendingApprovalCache;
import com.gvchat.platform.order.infra.persistence.mapper.CatalogItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.mapper.ProductMapper;
import com.gvchat.platform.order.infra.persistence.po.CatalogItemPo;
import com.gvchat.platform.order.infra.persistence.po.OrderItemPo;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 加项三条写路径（客户提交 / 确认 / 拒绝）与「待确认加项」提醒链路的**真实装配**回归守卫。
 *
 * <p><b>守的是什么（2026-09-19 线上 500）</b>：{@code OrderItemController#invalidatePendingApproval()}
 * 曾经误写成调用自身，写路径直接 {@code StackOverflowError} → 兜底 advice 回 500
 * {@code INTERNAL_ERROR 服务内部错误，请稍后重试}；而前端 store 吞掉异常后照样弹「已确认」，
 * 于是出现「前端说成功、后端说 500」的结果不一致。
 *
 * <p><b>为什么既有单测漏了</b>：{@code OrderItemControllerAuditTest} / {@code OrderItemControllerWebTest}
 * 的测试装配一律把 {@code pendingApprovalService} 传 {@code null}，null 分支立即返回，递归进不去；
 * 生产由 Spring 注入真实 Bean，才必然爆栈。因此本类**刻意注入真实的
 * {@link PendingApprovalApplicationService} + {@link PendingApprovalCache}**（只 mock 持久层），
 * 任何「自己调自己」的重入都会在这里以 StackOverflowError 直接失败。
 *
 * <p>断言同时覆盖业务口径本身：写路径必须让本门店的聚合缓存**立即失效**
 * （角标/卡片标记秒级更新，不等 TTL），失效后下一次读必须回源拿到新状态。
 */
class OrderItemControllerPendingApprovalTest {

    private static final Long ORDER_ID = 88L;
    private static final Long ITEM_ID = 501L;
    private static final Long CATALOG_ITEM_ID = 700L;
    private static final long TENANT_ID = 1001L;
    private static final Long STORE_ID = 2001L;

    private OrderItemMapper orderItemMapper;
    private CatalogItemMapper catalogItemMapper;
    private OrderMapper orderMapper;
    private ProductMapper productMapper;
    private KtvSessionApplicationService ktvSessionService;
    private PendingApprovalCache cache;
    private PendingApprovalApplicationService pendingApprovalService;
    private OrderItemController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        orderItemMapper = mock(OrderItemMapper.class);
        catalogItemMapper = mock(CatalogItemMapper.class);
        orderMapper = mock(OrderMapper.class);
        productMapper = mock(ProductMapper.class);
        ktvSessionService = mock(KtvSessionApplicationService.class);
        // TTL 与生产默认一致（3s）：缓存不会在测试期间自然过期，因此失效断言只可能来自写路径。
        cache = new PendingApprovalCache(3000);
        pendingApprovalService = new PendingApprovalApplicationService(
                orderItemMapper, orderMapper, ktvSessionService, cache);
        controller = new OrderItemController(orderItemMapper, catalogItemMapper,
                mock(SettlementApplicationService.class), orderMapper,
                mock(InventoryApplicationService.class), productMapper,
                mock(OrderAmountApplicationService.class), mock(AuditClient.class),
                null, pendingApprovalService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(orderMapper.selectBatchIds(any())).thenReturn(List.of(order("DRAFT")));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("DRAFT"));
        when(ktvSessionService.listByOrderIds(any())).thenReturn(Map.of());
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 42L, 1,
                List.of("order.add_item")));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /** 确认加项（PENDING_APPROVAL → ACTIVE）：不再爆栈，且本门店聚合缓存立即失效。 */
    @Test
    void confirm_invalidatesPendingApprovalCache() {
        seedCachedPendingView();
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(pendingItem());
        when(orderItemMapper.markApproval(any(), any(), any(), any(), any())).thenReturn(1);
        when(orderItemMapper.updateById(any(OrderItemPo.class))).thenReturn(1);

        OrderItemPo confirmed = controller.confirm(ORDER_ID, ITEM_ID);

        assertEquals("ACTIVE", confirmed.getStatus());
        assertCacheInvalidatedAndReloads();
    }

    /** 拒绝加项（PENDING_APPROVAL → REJECTED）：同样不爆栈、同样立即失效。 */
    @Test
    void reject_invalidatesPendingApprovalCache() {
        seedCachedPendingView();
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(pendingItem());
        when(orderItemMapper.markApproval(any(), any(), any(), any(), any())).thenReturn(1);

        OrderItemPo rejected = controller.reject(ORDER_ID, ITEM_ID);

        assertEquals("REJECTED", rejected.getStatus());
        assertCacheInvalidatedAndReloads();
    }

    /** 加项（客户提交 / 门店代客）：落库后提醒必须秒级出现，不能等 TTL。 */
    @Test
    void addItem_invalidatesPendingApprovalCache() {
        seedCachedPendingView();
        when(catalogItemMapper.selectById(CATALOG_ITEM_ID)).thenReturn(catalogItem());

        controller.addItem(ORDER_ID, new OrderItemController.AddItemRequest(
                TENANT_ID, "ADD_ON", CATALOG_ITEM_ID, null, null, null, BigDecimal.ONE, "CUSTOMER"));

        assertCacheInvalidatedAndReloads();
    }

    /**
     * 并发输家（条件更新影响 0 行）：必须回 **409** `ORDER_ITEM_STATUS_INVALID`。
     *
     * <p>两端（Web 后台 / B 端 H5）都只在 409 判定「已被别人处理」并静默以服务端为准刷新；
     * 落到 400 会让运营看到红色「操作失败」，与「其实已经处理好了」的真实结果相反。
     */
    @Test
    void confirm_losingConcurrencyRace_returns409() throws Exception {
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(pendingItem());
        when(orderItemMapper.markApproval(any(), any(), any(), any(), any())).thenReturn(0);

        mvc.perform(post("/business/orders/" + ORDER_ID + "/items/" + ITEM_ID + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ITEM_STATUS_INVALID"));
    }

    /** 前置：把「有 1 条待确认加项」的聚合视图灌进真实缓存（选择列表已回源 1 次）。 */
    private void seedCachedPendingView() {
        when(orderItemMapper.selectList(any())).thenReturn(List.of(pendingItem()));
        pendingApprovalService.pendingForCurrentStore();
        assertTrue(cache.get(TENANT_ID, STORE_ID).isPresent(), "前置条件：聚合视图必须已进本门店缓存");
    }

    /** 写路径后：缓存条目必须消失，且下一次读必须回源（selectList 第 2 次）。 */
    private void assertCacheInvalidatedAndReloads() {
        assertTrue(cache.get(TENANT_ID, STORE_ID).isEmpty(), "写路径后必须失效本门店聚合缓存（角标立即更新）");
        pendingApprovalService.pendingForCurrentStore();
        verify(orderItemMapper, times(2)).selectList(any());
    }

    private static OrderPo order(String status) {
        OrderPo po = new OrderPo();
        po.setId(ORDER_ID);
        po.setTenantId(TENANT_ID);
        po.setStoreId(STORE_ID);
        po.setStatus(status);
        po.setCurrencyCode("CNY");
        return po;
    }

    private static CatalogItemPo catalogItem() {
        CatalogItemPo po = new CatalogItemPo();
        po.setId(CATALOG_ITEM_ID);
        po.setTenantId(TENANT_ID);
        po.setStoreId(STORE_ID);
        po.setName("可乐");
        po.setItemType("PRODUCT");
        po.setUnitPrice(new BigDecimal("500"));
        po.setStatus("ACTIVE");
        po.setStockControlled(false);
        return po;
    }

    private static OrderItemPo pendingItem() {
        OrderItemPo po = new OrderItemPo();
        po.setId(ITEM_ID);
        po.setTenantId(TENANT_ID);
        po.setOrderId(ORDER_ID);
        po.setNameSnapshot("可乐");
        po.setQuantity(BigDecimal.ONE);
        po.setUnitPrice(new BigDecimal("500"));
        po.setTotalAmount(new BigDecimal("500"));
        po.setCurrencyCode("CNY");
        po.setStatus("PENDING_APPROVAL");
        po.setInventoryStatus("NOT_APPLICABLE");
        po.setCreatedAt(LocalDateTime.of(2026, 9, 19, 20, 0, 0));
        return po;
    }
}
