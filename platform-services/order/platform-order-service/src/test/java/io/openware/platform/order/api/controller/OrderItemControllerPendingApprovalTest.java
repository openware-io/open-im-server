package io.openware.platform.order.api.controller;

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

import io.openware.platform.order.api.dto.OrderItemView;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.InventoryApplicationService;
import io.openware.platform.order.application.KtvSessionApplicationService;
import io.openware.platform.order.application.OrderAmountApplicationService;
import io.openware.platform.order.application.PendingApprovalApplicationService;
import io.openware.platform.order.application.SettlementApplicationService;
import io.openware.platform.order.handler.GlobalExceptionHandler;
import io.openware.platform.order.infra.cache.PendingApprovalCache;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.domain.order.repository.OrderItemRepository;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.model.OrderItem;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.repository.AuditSnapshot;
import io.openware.platform.order.domain.order.repository.Persisted;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private OrderItemRepository orderItemMapper;
    private CatalogItemRepository catalogItemRepository;
    private OrderRepository orderMapper;
    private ProductRepository productRepository;
    private KtvSessionApplicationService ktvSessionService;
    private PendingApprovalCache cache;
    private PendingApprovalApplicationService pendingApprovalService;
    private OrderItemController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        orderItemMapper = mock(OrderItemRepository.class);
        catalogItemRepository = mock(CatalogItemRepository.class);
        orderMapper = mock(OrderRepository.class);
        productRepository = mock(ProductRepository.class);
        ktvSessionService = mock(KtvSessionApplicationService.class);
        // TTL 与生产默认一致（3s）：缓存不会在测试期间自然过期，因此失效断言只可能来自写路径。
        cache = new PendingApprovalCache(3000);        pendingApprovalService = new PendingApprovalApplicationService(
                orderItemMapper, orderMapper, ktvSessionService, cache);
        controller = new OrderItemController(orderItemMapper, catalogItemRepository,
                mock(SettlementApplicationService.class), orderMapper,
                mock(InventoryApplicationService.class), productRepository,
                mock(OrderAmountApplicationService.class), mock(AuditClient.class),
                null, pendingApprovalService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(orderMapper.findByIds(any())).thenReturn(List.of(order("DRAFT")));
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.of(order("DRAFT")));
        when(ktvSessionService.listByOrderIds(any())).thenReturn(Map.of());
        // 端口约定 insert 返回「带主键的聚合」：mock 回填主键，控制器要用返回值继续。
        when(orderItemMapper.insert(any(OrderItem.class))).thenAnswer(invocation -> {
            OrderItem inserted = invocation.getArgument(0);
            return inserted.withId(ITEM_ID);
        });
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
        when(orderItemMapper.findById(ITEM_ID)).thenReturn(Optional.of(pendingItem()));
        when(orderItemMapper.markApproval(any(), any(), any(), any(), any())).thenReturn(true);

        OrderItemView confirmed = controller.confirm(ORDER_ID, ITEM_ID);

        assertEquals("ACTIVE", confirmed.status());
        assertCacheInvalidatedAndReloads();
    }

    /** 拒绝加项（PENDING_APPROVAL → REJECTED）：同样不爆栈、同样立即失效。 */
    @Test
    void reject_invalidatesPendingApprovalCache() {
        seedCachedPendingView();
        when(orderItemMapper.findById(ITEM_ID)).thenReturn(Optional.of(pendingItem()));
        when(orderItemMapper.markApproval(any(), any(), any(), any(), any())).thenReturn(true);

        OrderItemView rejected = controller.reject(ORDER_ID, ITEM_ID);

        assertEquals("REJECTED", rejected.status());
        assertCacheInvalidatedAndReloads();
    }

    /** 加项（客户提交 / 门店代客）：落库后提醒必须秒级出现，不能等 TTL。 */
    @Test
    void addItem_invalidatesPendingApprovalCache() {
        seedCachedPendingView();
        when(catalogItemRepository.findById(CATALOG_ITEM_ID)).thenReturn(Optional.of(catalogItem()));

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
        when(orderItemMapper.findById(ITEM_ID)).thenReturn(Optional.of(pendingItem()));
        when(orderItemMapper.markApproval(any(), any(), any(), any(), any())).thenReturn(false);

        mvc.perform(post("/business/orders/" + ORDER_ID + "/items/" + ITEM_ID + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ITEM_STATUS_INVALID"));
    }

    /** 前置：把「有 1 条待确认加项」的聚合视图灌进真实缓存（选择列表已回源 1 次）。 */
    private void seedCachedPendingView() {
        when(orderItemMapper.list(any())).thenReturn(List.of(new Persisted<>(pendingItem(),
                new AuditSnapshot(null, LocalDateTime.of(2026, 9, 19, 20, 0, 0), null,
                        LocalDateTime.of(2026, 9, 19, 20, 0, 0)))));
        pendingApprovalService.pendingForCurrentStore();
        assertTrue(cache.get(TENANT_ID, STORE_ID).isPresent(), "前置条件：聚合视图必须已进本门店缓存");
    }

    /** 写路径后：缓存条目必须消失，且下一次读必须回源（selectList 第 2 次）。 */
    private void assertCacheInvalidatedAndReloads() {
        assertTrue(cache.get(TENANT_ID, STORE_ID).isEmpty(), "写路径后必须失效本门店聚合缓存（角标立即更新）");
        pendingApprovalService.pendingForCurrentStore();
        verify(orderItemMapper, times(2)).list(any());
    }

    /** 订单夹具：领域模型不可变，用规范构造器一次成型（审计列不在模型上）。 */
    private static Order order(String status) {
        return new Order(ORDER_ID, TENANT_ID, null, STORE_ID, "ORD-20260918-0001", null, "KTV", null, status,
                "CNY", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, null, null, 0);
    }

    private static CatalogItem catalogItem() {
        return new CatalogItem(CATALOG_ITEM_ID, null, TENANT_ID, STORE_ID, "酒水", "PRODUCT", "可乐", "瓶",
                new BigDecimal("500"), null, List.of(), null, "ACTIVE", false, 0);
    }

    private static OrderItem pendingItem() {
        // 待确认加项：提交时间 createdAt 是记账列（不进领域模型），由读路径的记账快照带回。
        return new OrderItem(ITEM_ID, TENANT_ID, STORE_ID, "KTV", ORDER_ID, "ADD_ON", null, null, "可乐",
                new BigDecimal("500"), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("500"), "CNY", null, OrderItem.STATUS_PENDING_APPROVAL,
                OrderItem.SOURCE_CUSTOMER, "NOT_APPLICABLE", null, null, null, null, null);
    }
}
