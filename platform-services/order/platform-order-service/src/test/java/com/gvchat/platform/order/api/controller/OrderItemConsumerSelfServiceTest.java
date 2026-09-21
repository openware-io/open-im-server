package com.gvchat.platform.order.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.application.SettlementApplicationService;
import com.gvchat.platform.order.handler.GlobalExceptionHandler;
import com.gvchat.platform.order.infra.persistence.mapper.CatalogItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.CustomerLookupMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.mapper.ProductMapper;
import com.gvchat.platform.order.infra.persistence.po.CatalogItemPo;
import com.gvchat.platform.order.infra.persistence.po.OrderItemPo;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * A380 C 端「加服务项」授权的回归测试。
 *
 * <p><b>线上缺陷</b>：{@code GET/POST /business/orders/{id}/items} 旧实现无条件要求商户权限码
 * {@code order.view} / {@code order.add_item}。这两个码只授予 B 端角色（V22：tenant.owner /
 * store.manager / store.cashier / store.finance）；而 C 端消费者会话的权限快照来自
 * {@code iam_consumer_application.permissions_json}，V14 种子只有
 * {@code reservation.view} / {@code reservation.create}。于是 C 端「加服务项」页面的第一个 await
 * 就拿到 403 PERMISSION_DENIED，异常冒泡出 async 渲染函数，页面永远停在「加载中…」。
 *
 * <p><b>修复口径</b>：持商户权限码的会话行为完全不变；未持有者按「本人订单」授权
 * （account_id → cst_member → ord_order.customer_id，与 {@code GET /me/orders} 同一口径），
 * 且加项 source 被强制成 {@code CUSTOMER}（PENDING_APPROVAL），
 * 不允许消费者自报 {@code MERCHANT} 绕过服务人员确认。
 */
class OrderItemConsumerSelfServiceTest {

    private static final long TENANT_ID = 100L;
    private static final long ACCOUNT_ID = 42L;
    private static final Long STORE_ID = 100L;
    private static final Long ORDER_ID = 9L;
    private static final Long MEMBER_ID = 777L;
    private static final Long OTHER_MEMBER_ID = 888L;
    private static final Long CATALOG_ITEM_ID = 10L;
    /** V14 种子给 saas-a380-c 的全部权限：消费者会话在订单域没有任何商户权限码。 */
    private static final List<String> CONSUMER_PERMISSIONS =
            List.of("reservation.view", "reservation.create");

    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final CatalogItemMapper catalogItemMapper = mock(CatalogItemMapper.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final CustomerLookupMapper customerLookupMapper = mock(CustomerLookupMapper.class);

    private final OrderItemController controller = new OrderItemController(orderItemMapper, catalogItemMapper,
            mock(SettlementApplicationService.class), orderMapper, null, productMapper, null,
            AuditClient.disabled(), customerLookupMapper, null);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    // —— 读取加项列表：曾经的 403 是「一直加载中」的直接原因 ——

    @Test
    void consumerSession_listsOwnOrderItems() throws Exception {
        signInConsumer();
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(MEMBER_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MEMBER_ID));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item("PENDING_APPROVAL")));

        mvc.perform(get("/business/orders/9/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING_APPROVAL"));
    }

    @Test
    void consumerSession_cannotReadAnotherMembersOrderItems() throws Exception {
        signInConsumer();
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(MEMBER_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OTHER_MEMBER_ID));

        mvc.perform(get("/business/orders/9/items"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORDER_SCOPE_DENIED"));
    }

    /**
     * 既没有商户权限码、又解析不出会员档案（例如账号还没建 cst_member）时，
     * 保持修复前的 403 PERMISSION_DENIED：这曾经是**每一个** C 端消费者请求该接口拿到的响应。
     */
    @Test
    void consumerSessionWithoutMemberProfile_keepsLegacyPermissionDenied() throws Exception {
        signInConsumer();
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(null);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MEMBER_ID));

        mvc.perform(get("/business/orders/9/items"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    /** B 端持有 order.view：行为完全不变，可查看任意订单，且不查会员归属。 */
    @Test
    void merchantWithOrderViewPermission_readsAnyOrderItems() throws Exception {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, STORE_ID, ACCOUNT_ID, 1,
                List.of("order.view")));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OTHER_MEMBER_ID));
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        mvc.perform(get("/business/orders/9/items")).andExpect(status().isOk());

        verify(customerLookupMapper, never()).findMemberId(anyLong(), anyLong());
    }

    // —— 加项：消费者自助（本人订单 + 强制 PENDING_APPROVAL） ——

    @Test
    void consumerAddItem_isForcedToCustomerSourceAndPendingApproval() throws Exception {
        signInConsumer();
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(MEMBER_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MEMBER_ID));
        when(catalogItemMapper.selectById(CATALOG_ITEM_ID)).thenReturn(serviceCatalog());

        // 客户端自报 MERCHANT：必须被服务端改写，否则消费者能绕过服务人员确认直接生效。
        mvc.perform(post("/business/orders/9/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogItemId\":10,\"quantity\":1,\"source\":\"MERCHANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("CUSTOMER"))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.unitPrice").value(3000))
                .andExpect(jsonPath("$.inventoryStatus").value("NOT_APPLICABLE"));
    }

    @Test
    void consumerAddItem_cannotTargetAnotherMembersOrder() throws Exception {
        signInConsumer();
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(MEMBER_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OTHER_MEMBER_ID));

        mvc.perform(post("/business/orders/9/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogItemId\":10,\"quantity\":1,\"source\":\"CUSTOMER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORDER_SCOPE_DENIED"));
    }

    /** B 端持有 order.add_item：source 原样透传（MERCHANT 直接生效），行为不变。 */
    @Test
    void merchantAddItem_keepsMerchantSourceAndActiveStatus() throws Exception {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, STORE_ID, ACCOUNT_ID, 1,
                List.of("order.add_item")));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OTHER_MEMBER_ID));
        when(catalogItemMapper.selectById(CATALOG_ITEM_ID)).thenReturn(serviceCatalog());

        mvc.perform(post("/business/orders/9/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogItemId\":10,\"quantity\":1,\"source\":\"MERCHANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MERCHANT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /** 确认/驳回加项仍然是服务人员专属动作：消费者会话不得把待确认项改生效。 */
    @Test
    void consumerCannotConfirmOrRejectOrderItems() throws Exception {
        signInConsumer();
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(MEMBER_ID));
        when(orderItemMapper.selectById(501L)).thenReturn(item("PENDING_APPROVAL"));

        mvc.perform(post("/business/orders/9/items/501/confirm"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        mvc.perform(post("/business/orders/9/items/501/reject"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    /** C 端消费者会话的真实权限快照（iam_consumer_application.permissions_json）。 */
    private static void signInConsumer() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, STORE_ID, ACCOUNT_ID, 1,
                CONSUMER_PERMISSIONS));
    }

    private static OrderPo order(Long customerId) {
        OrderPo order = new OrderPo();
        order.setId(ORDER_ID);
        order.setTenantId(TENANT_ID);
        order.setStoreId(STORE_ID);
        order.setStatus("SERVING");
        order.setCurrencyCode("CNY");
        order.setCustomerId(customerId);
        return order;
    }

    /** 纯服务目录项：未关联商品、不占库存，单价来自目录自身（「加项能对服务加项」）。 */
    private static CatalogItemPo serviceCatalog() {
        CatalogItemPo catalog = new CatalogItemPo();
        catalog.setId(CATALOG_ITEM_ID);
        catalog.setTenantId(TENANT_ID);
        catalog.setStoreId(STORE_ID);
        catalog.setStatus("ACTIVE");
        catalog.setName("陪唱服务");
        catalog.setUnit("次");
        catalog.setItemType("SERVICE");
        catalog.setStockControlled(false);
        catalog.setUnitPrice(new BigDecimal("3000"));
        return catalog;
    }

    private static OrderItemPo item(String status) {
        OrderItemPo item = new OrderItemPo();
        item.setId(501L);
        item.setOrderId(ORDER_ID);
        item.setNameSnapshot("陪唱服务");
        item.setStatus(status);
        item.setSource("CUSTOMER");
        item.setInventoryStatus("NOT_APPLICABLE");
        return item;
    }
}
