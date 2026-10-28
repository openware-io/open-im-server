package io.openware.platform.order.api.controller;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.order.application.InventoryApplicationService;
import io.openware.platform.order.application.OrderAmountApplicationService;
import io.openware.platform.order.application.OrderItemApplicationService;
import io.openware.platform.order.application.SettlementApplicationService;
import io.openware.platform.order.handler.GlobalExceptionHandler;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.model.Product;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.domain.order.repository.OrderItemRepository;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.RefundStatus;
import io.openware.platform.order.domain.order.model.OrderItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OrderItemController Web 层测试：settle 权限守卫 + 加项库存口径（以商品为准、库存不足回 409）。 */
class OrderItemControllerWebTest {

    private final OrderItemRepository orderItemMapper = mock(OrderItemRepository.class);
    private final CatalogItemRepository catalogItemRepository = mock(CatalogItemRepository.class);
    private final OrderRepository orderMapper = mock(OrderRepository.class);
    private final InventoryApplicationService inventoryService = mock(InventoryApplicationService.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private Order settlementResult;
    private final SettlementApplicationService settlementService = new SettlementApplicationService(
            orderMapper,
            new AuditClient(RestClient.builder().baseUrl("http://localhost").build(), "test"),
            new OrderAmountApplicationService(orderMapper, orderItemMapper)) {
        @Override
        public Order settle(Long orderId, int expectedVersion) {
            return settlementResult;
        }
    };

    private final OrderItemApplicationService plainItemService = new OrderItemApplicationService(
            orderItemMapper, catalogItemRepository, orderMapper, null, null, null,
            AuditClient.disabled(), null, null);
    private final OrderItemApplicationService stockItemService = new OrderItemApplicationService(
            orderItemMapper, catalogItemRepository, orderMapper, inventoryService, productRepository, null,
            AuditClient.disabled(), null, null);

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new OrderItemController(plainItemService, settlementService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    /** 带库存能力的装配（商品端口 + inventoryService），用于验证库存口径。 */
    private final MockMvc stockMvc = MockMvcBuilders
            .standaloneSetup(new OrderItemController(stockItemService, settlementService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /**
     * 端口约定 {@code insert} 返回「带主键的聚合」：mock 必须回填主键，
     * 控制器要用返回值继续（库存扣减、审计与响应都读它）。
     */
    @BeforeEach
    void stubItemInsert() {
        when(orderItemMapper.insert(any(OrderItem.class))).thenAnswer(invocation -> {
            OrderItem inserted = invocation.getArgument(0);
            return inserted.withId(501L);
        });
    }

    @Test
    void settle_withoutPermission_returns403PermissionDenied() throws Exception {
        TenantContextHolder.set(new TenantContext(1L, 1L, 1L, 0L, 0));

        mvc.perform(post("/business/orders/1/settle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void settle_withPermission_delegatesToService() throws Exception {
        TenantContextHolder.set(new TenantContext(1L, 1L, 1L, 0L, 0, List.of("order.settle")));
        settlementResult = order();

        mvc.perform(post("/business/orders/1/settle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk());
    }

    @Test
    void addItem_withTenantContext_usesOrderStoreCatalog() throws Exception {
        TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1, List.of("order.add_item")));
        Order order = order();
        CatalogItem catalog = catalog(10L, 100L, "PRODUCT", "百威啤酒", "瓶", new BigDecimal("1500"), false);
        when(orderMapper.findById(1L)).thenReturn(Optional.of(order));
        when(catalogItemRepository.findById(10L)).thenReturn(Optional.of(catalog));

        mvc.perform(post("/business/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogItemId\":10,\"quantity\":1,\"source\":\"MERCHANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogItemId").value(10));
    }

    @Test
    void addItem_withDifferentStoreContext_returns403() throws Exception {
        TenantContextHolder.set(new TenantContext(100L, null, 101L, 1L, 1, List.of("order.add_item")));
        Order order = order();
        CatalogItem catalog = catalog(10L, 100L, "PRODUCT", "实物商品", "瓶", new BigDecimal("1000"), false);
        when(orderMapper.findById(1L)).thenReturn(Optional.of(order));
        when(catalogItemRepository.findById(10L)).thenReturn(Optional.of(catalog));

        mvc.perform(post("/business/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogItemId\":10,\"quantity\":1,\"source\":\"MERCHANT\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_SCOPE_DENIED"));
    }

    /**
     * F3 关键回归：目录项 stock_controlled = 0（V17 回填之前的历史/缓存脏数据），
     * 但关联商品是实物（stock_controlled = 1）——加项必须照样扣库存，库存不足回 409。
     */
    @Test
    void addItem_consumesStockWhenProductSaysStockControlledEvenIfCatalogCacheIsStale() throws Exception {
        givenStockControlledScenario();
        doThrow(new BusinessException("INVENTORY_INSUFFICIENT", "可用库存不足"))
                .when(inventoryService).changeStock(any(), any(), any(), any(), any(), any(), any());

        stockMvc.perform(post("/business/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogItemId\":10,\"quantity\":1,\"source\":\"MERCHANT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_INSUFFICIENT"))
                .andExpect(jsonPath("$.message").value("可用库存不足"));

        // 确实按商品的物料扣减（55），而不是因为目录项缓存列是 0 就整段跳过
        verify(inventoryService).changeStock(eq(55L), any(), any(), any(), any(), any(), any());
    }

    /** 有库存时加项成功，且明细上带出待扣减物料 id（服务端快照）。 */
    @Test
    void addItem_succeedsAndRecordsInventoryMaterial() throws Exception {
        givenStockControlledScenario();

        stockMvc.perform(post("/business/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogItemId\":10,\"quantity\":1,\"source\":\"MERCHANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogItemId").value(10))
                .andExpect(jsonPath("$.inventoryMaterialId").value(55))
                .andExpect(jsonPath("$.inventoryStatus").value("CONSUMED"));
    }

    /** 非实物商品（商品 stock_controlled = false）不扣库存，即使目录项缓存列是 1。 */
    @Test
    void addItem_doesNotTouchInventoryForNonStockProduct() throws Exception {
        TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1, List.of("order.add_item")));
        when(orderMapper.findById(1L)).thenReturn(Optional.of(order()));
        when(catalogItemRepository.findById(10L))
                .thenReturn(Optional.of(catalog(10L, 100L, "PRODUCT", "实物商品", "瓶", new BigDecimal("1000"), true)));
        when(productRepository.find(any(ProductRepository.ProductQuery.class)))
                .thenReturn(List.of(product(50L, 55L, "PRODUCT", false)));

        stockMvc.perform(post("/business/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogItemId\":10,\"quantity\":1,\"source\":\"MERCHANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventoryStatus").value("NOT_APPLICABLE"));

        verify(inventoryService, never()).changeStock(any(), any(), any(), any(), any(), any(), any());
    }

    /** 商品说占库存、但未上架/未关联物料：明确拒绝，不能默默按不扣库存处理。 */
    @Test
    void addItem_rejectsWhenStockControlledProductHasNoMaterial() throws Exception {
        TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1, List.of("order.add_item")));
        when(orderMapper.findById(1L)).thenReturn(Optional.of(order()));
        when(catalogItemRepository.findById(10L))
                .thenReturn(Optional.of(catalog(10L, 100L, "PRODUCT", "实物商品", "瓶", new BigDecimal("1000"), false)));
        when(productRepository.find(any(ProductRepository.ProductQuery.class)))
                .thenReturn(List.of(product(50L, null, "PRODUCT", true)));

        stockMvc.perform(post("/business/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogItemId\":10,\"quantity\":1,\"source\":\"MERCHANT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_AVAILABLE"));

        verify(inventoryService, never()).changeStock(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * 加项能对服务加项（第 4 点）：目录项 item_type=SERVICE、关联商品是服务型
     * （stock_controlled=0、无物料）——按其目录单价计费，不校验库存、不写库存流水。
     */
    @Test
    void addItem_serviceItemBillsCatalogPriceAndNeverTouchesInventory() throws Exception {
        TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1, List.of("order.add_item")));
        when(orderMapper.findById(1L)).thenReturn(Optional.of(order()));
        when(catalogItemRepository.findById(10L)).thenReturn(Optional.of(
                catalog(10L, 100L, "SERVICE", "陪唱服务", "次", new BigDecimal("3000"), false)));
        when(productRepository.find(any(ProductRepository.ProductQuery.class)))
                .thenReturn(List.of(product(50L, null, "SERVICE", false)));

        stockMvc.perform(post("/business/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogItemId\":10,\"quantity\":2,\"source\":\"MERCHANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemType").value("SERVICE"))
                .andExpect(jsonPath("$.unitPrice").value(3000))
                .andExpect(jsonPath("$.totalAmount").value(6000))
                .andExpect(jsonPath("$.inventoryStatus").value("NOT_APPLICABLE"));

        verify(inventoryService, never()).changeStock(any(), any(), any(), any(), any(), any(), any());
    }

    /** 纯服务目录项（未关联商品）：同样按目录单价计费、不校验库存（加项能对服务加项）。 */
    @Test
    void addItem_standaloneServiceCatalogItemNeedsNoProduct() throws Exception {
        TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1, List.of("order.add_item")));
        when(orderMapper.findById(1L)).thenReturn(Optional.of(order()));
        // 故意把缓存列标成「占库存」：SERVICE 一律不扣库存，不能被缓存脏数据带偏。
        when(catalogItemRepository.findById(10L)).thenReturn(Optional.of(
                catalog(10L, 100L, "SERVICE", "清洁服务", "次", new BigDecimal("2000"), true)));
        // 没关联商品（端口查不到）：服务项照样可加，不扣库存。
        when(productRepository.find(any(ProductRepository.ProductQuery.class))).thenReturn(List.of());

        stockMvc.perform(post("/business/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogItemId\":10,\"quantity\":1,\"source\":\"MERCHANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemType").value("SERVICE"))
                .andExpect(jsonPath("$.inventoryStatus").value("NOT_APPLICABLE"));

        verify(inventoryService, never()).changeStock(any(), any(), any(), any(), any(), any(), any());
    }

    private void givenStockControlledScenario() {
        TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1, List.of("order.add_item")));
        when(orderMapper.findById(1L)).thenReturn(Optional.of(order()));
        // 目录项缓存列是 0：历史数据还没被 V17 回填
        when(catalogItemRepository.findById(10L))
                .thenReturn(Optional.of(catalog(10L, 100L, "PRODUCT", "实物商品", "瓶", new BigDecimal("1000"), false)));
        when(productRepository.find(any(ProductRepository.ProductQuery.class)))
                .thenReturn(List.of(product(50L, 55L, "PRODUCT", true)));
    }

    /** 订单夹具：领域模型不可变，用规范构造器一次成型（审计列不在模型上）。 */
    private static Order order() {
        return new Order(1L, 100L, null, 100L, "ORD-20260918-0001", null, "KTV", null, "DRAFT", RefundStatus.NONE.name(), "CNY",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, 0);
    }

    /** 目录项夹具（不可变记录；可变字段作为参数显式给出，替代迁移前的 setter 逐项覆盖）。 */
    private static CatalogItem catalog(Long id, Long storeId, String itemType, String name, String unit,
                                       BigDecimal unitPrice, boolean stockControlled) {
        return new CatalogItem(id, null, 100L, storeId, "酒水", itemType, name, unit, unitPrice, null, List.of(),
                null, "ACTIVE", stockControlled, 0);
    }

    /** 商品夹具（不可变记录）。 */
    private static Product product(Long id, Long materialId, String itemType, boolean stockControlled) {
        return new Product(id, 100L, 100L, "P-" + id, "实物商品", "酒水", itemType, "瓶",
                new BigDecimal("1000"), materialId, null, stockControlled, 10L, "ON_SHELF", 0, null, List.of(),
                null, 0);
    }
}
