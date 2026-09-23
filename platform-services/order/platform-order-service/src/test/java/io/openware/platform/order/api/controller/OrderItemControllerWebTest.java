package io.openware.platform.order.api.controller;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.order.application.InventoryApplicationService;
import io.openware.platform.order.application.OrderAmountApplicationService;
import io.openware.platform.order.application.SettlementApplicationService;
import io.openware.platform.order.handler.GlobalExceptionHandler;
import io.openware.platform.order.infra.persistence.mapper.CatalogItemMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderItemMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.mapper.ProductMapper;
import io.openware.platform.order.infra.persistence.po.CatalogItemPo;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import io.openware.platform.order.infra.persistence.po.ProductPo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.math.BigDecimal;

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

    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final CatalogItemMapper catalogItemMapper = mock(CatalogItemMapper.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final InventoryApplicationService inventoryService = mock(InventoryApplicationService.class);
    private final ProductMapper productMapper = mock(ProductMapper.class);
    private OrderPo settlementResult;
    private final SettlementApplicationService settlementService = new SettlementApplicationService(
            orderMapper, orderItemMapper,
            new AuditClient(RestClient.builder().baseUrl("http://localhost").build(), "test"),
            new OrderAmountApplicationService(orderMapper, orderItemMapper)) {
        @Override
        public OrderPo settle(Long orderId, int expectedVersion) {
            return settlementResult;
        }
    };

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new OrderItemController(orderItemMapper, catalogItemMapper, settlementService, orderMapper))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    /** 带库存能力的装配（productMapper + inventoryService），用于验证库存口径。 */
    private final MockMvc stockMvc = MockMvcBuilders
            .standaloneSetup(new OrderItemController(orderItemMapper, catalogItemMapper, settlementService, orderMapper,
                    inventoryService, productMapper, null))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
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
        settlementResult = new OrderPo();

        mvc.perform(post("/business/orders/1/settle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk());
    }

    @Test
    void addItem_withTenantContext_usesOrderStoreCatalog() throws Exception {
        TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1, List.of("order.add_item")));
        OrderPo order = new OrderPo();
        order.setTenantId(100L);
        order.setStoreId(100L);
        CatalogItemPo catalog = new CatalogItemPo();
        catalog.setId(10L);
        catalog.setTenantId(100L);
        catalog.setStoreId(100L);
        catalog.setStatus("ACTIVE");
        catalog.setName("百威啤酒");
        catalog.setUnit("瓶");
        catalog.setUnitPrice(new BigDecimal("1500"));
        catalog.setItemType("PRODUCT");
        catalog.setStockControlled(false);
        when(orderMapper.selectById(1L)).thenReturn(order);
        when(catalogItemMapper.selectById(10L)).thenReturn(catalog);

        mvc.perform(post("/business/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogItemId\":10,\"quantity\":1,\"source\":\"MERCHANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogItemId").value(10));
    }

    @Test
    void addItem_withDifferentStoreContext_returns403() throws Exception {
        TenantContextHolder.set(new TenantContext(100L, null, 101L, 1L, 1, List.of("order.add_item")));
        OrderPo order = new OrderPo();
        order.setTenantId(100L);
        order.setStoreId(100L);
        CatalogItemPo catalog = new CatalogItemPo();
        catalog.setTenantId(100L);
        catalog.setStoreId(100L);
        catalog.setStatus("ACTIVE");
        when(orderMapper.selectById(1L)).thenReturn(order);
        when(catalogItemMapper.selectById(10L)).thenReturn(catalog);

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
        when(orderMapper.selectById(1L)).thenReturn(order());
        CatalogItemPo catalog = catalog();
        catalog.setStockControlled(true);
        when(catalogItemMapper.selectById(10L)).thenReturn(catalog);
        ProductPo product = product();
        product.setStockControlled(false);
        when(productMapper.selectOne(any())).thenReturn(product);

        stockMvc.perform(post("/business/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogItemId\":10,\"quantity\":1,\"source\":\"MERCHANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventoryStatus").value("NOT_APPLICABLE"));

        verify(inventoryService, never()).changeStock(any(), any(), any(), any(), any(), any(), any());
    }

    /** 商品说占库存、但未上架/未关联物料：明确拒绝，不能默默按不扣库存处理。 */
    @Test
    void addItem_rejectsWhenStockControlledProductHasNoMaterial() throws Exception {        TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1, List.of("order.add_item")));
        when(orderMapper.selectById(1L)).thenReturn(order());
        when(catalogItemMapper.selectById(10L)).thenReturn(catalog());
        ProductPo product = product();
        product.setMaterialId(null);
        when(productMapper.selectOne(any())).thenReturn(product);

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
        when(orderMapper.selectById(1L)).thenReturn(order());
        CatalogItemPo catalog = catalog();
        catalog.setItemType("SERVICE");
        catalog.setName("陪唱服务");
        catalog.setUnit("次");
        catalog.setUnitPrice(new BigDecimal("3000"));
        catalog.setStockControlled(false);
        when(catalogItemMapper.selectById(10L)).thenReturn(catalog);
        ProductPo product = product();
        product.setItemType("SERVICE");
        product.setStockControlled(false);
        product.setMaterialId(null);
        product.setServerResourceId(9L);
        when(productMapper.selectOne(any())).thenReturn(product);

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
        when(orderMapper.selectById(1L)).thenReturn(order());
        CatalogItemPo catalog = catalog();
        catalog.setItemType("SERVICE");
        catalog.setName("清洁服务");
        catalog.setUnitPrice(new BigDecimal("2000"));
        // 故意把缓存列标成「占库存」：SERVICE 一律不扣库存，不能被缓存脏数据带偏。
        catalog.setStockControlled(true);
        when(catalogItemMapper.selectById(10L)).thenReturn(catalog);
        // 没关联商品（productMapper 查不到）：服务项照样可加，不扣库存。
        when(productMapper.selectOne(any())).thenReturn(null);

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
        when(orderMapper.selectById(1L)).thenReturn(order());
        // 目录项缓存列是 0：历史数据还没被 V17 回填
        CatalogItemPo catalog = catalog();
        catalog.setStockControlled(false);
        when(catalogItemMapper.selectById(10L)).thenReturn(catalog);
        when(productMapper.selectOne(any())).thenReturn(product());
    }

    private static OrderPo order() {
        OrderPo order = new OrderPo();
        order.setId(1L);
        order.setTenantId(100L);
        order.setStoreId(100L);
        order.setStatus("SERVING");
        return order;
    }

    private static CatalogItemPo catalog() {
        CatalogItemPo catalog = new CatalogItemPo();
        catalog.setId(10L);
        catalog.setTenantId(100L);
        catalog.setStoreId(100L);
        catalog.setStatus("ACTIVE");
        catalog.setName("实物商品");
        catalog.setUnit("瓶");
        catalog.setUnitPrice(new BigDecimal("1000"));
        catalog.setItemType("PRODUCT");
        return catalog;
    }

    private static ProductPo product() {
        ProductPo product = new ProductPo();
        product.setId(50L);
        product.setTenantId(100L);
        product.setStoreId(100L);
        product.setCatalogItemId(10L);
        product.setStockControlled(true);
        product.setMaterialId(55L);
        product.setStatus("ON_SHELF");
        return product;
    }
}
