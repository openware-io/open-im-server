package io.openware.platform.order.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.currency.Currency;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.platform.order.api.dto.OrderItemView;
import io.openware.platform.order.api.dto.OrderView;
import io.openware.platform.order.application.SettlementApplicationService;
import io.openware.platform.order.application.InventoryApplicationService;
import io.openware.platform.order.application.OrderAmountApplicationService;
import io.openware.platform.order.application.PendingApprovalApplicationService;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.model.Product;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.OrderItem;
import io.openware.platform.order.domain.order.repository.MemberLookupProvider;
import io.openware.platform.order.domain.order.repository.OrderItemQuery;
import io.openware.platform.order.domain.order.repository.OrderItemRepository;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/business/orders/{orderId}")
public class OrderItemController {

    /** 商户加项权限码：服务人员/收银员可对任意订单加项（可声明 source=MERCHANT 直接生效）。 */
    private static final String PERMISSION_ADD_ITEM = "order.add_item";
    /** 商户查看订单明细权限码：持有它可查看任意订单的加项列表。 */
    private static final String PERMISSION_VIEW_ORDER = "order.view";
    /** 消费者自助加项的来源：PENDING_APPROVAL 待服务人员确认，不计入应收。 */
    private static final String SOURCE_CUSTOMER = "CUSTOMER";

    private final OrderItemRepository orderItemRepository;
    private final CatalogItemRepository catalogItemRepository;
    private final SettlementApplicationService settlementService;
    private final OrderRepository orderRepository;
    private final InventoryApplicationService inventoryService;
    private final ProductRepository productRepository;
    private final OrderAmountApplicationService orderAmounts;
    private final AuditClient auditClient;
    /** account_id → cst_member：消费者自助路径的订单归属校验（与 /me/orders 同一份会员口径）。 */
    private final MemberLookupProvider memberLookupProvider;
    /**
     * 待确认加项聚合（角标/卡片标记的数据源）：确认/拒绝/客户提交后失效缓存，保证提醒**及时更新**。
     * 兼容装配下可为 null（单测），失效逻辑按空操作处理。
     */
    private final PendingApprovalApplicationService pendingApprovalService;

    @Autowired
    public OrderItemController(OrderItemRepository orderItemRepository,
                               CatalogItemRepository catalogItemRepository,
                               SettlementApplicationService settlementService, OrderRepository orderRepository,
                               InventoryApplicationService inventoryService, ProductRepository productRepository,
                               OrderAmountApplicationService orderAmounts, AuditClient auditClient,
                               MemberLookupProvider memberLookupProvider,
                               PendingApprovalApplicationService pendingApprovalService) {
        this.orderItemRepository = orderItemRepository;
        this.catalogItemRepository = catalogItemRepository;
        this.settlementService = settlementService;
        this.orderRepository = orderRepository;
        this.inventoryService = inventoryService;
        this.productRepository = productRepository;
        this.orderAmounts = orderAmounts;
        this.auditClient = auditClient;
        this.memberLookupProvider = memberLookupProvider;
        this.pendingApprovalService = pendingApprovalService;
    }

    /** 兼容既有 Web 层测试与外部装配；库存能力仅在库存商品加项路径使用，审计用关闭态客户端。 */
    public OrderItemController(OrderItemRepository orderItemRepository,
                               CatalogItemRepository catalogItemRepository,
                               SettlementApplicationService settlementService, OrderRepository orderRepository) {
        this(orderItemRepository, catalogItemRepository, settlementService, orderRepository, null, null, null,
                AuditClient.disabled(), null, null);
    }

    /** 兼容既有 Web 层测试（库存 + 商品 + 金额，不含审计）。 */
    public OrderItemController(OrderItemRepository orderItemRepository,
                               CatalogItemRepository catalogItemRepository,
                               SettlementApplicationService settlementService, OrderRepository orderRepository,
                               InventoryApplicationService inventoryService, ProductRepository productRepository,
                               OrderAmountApplicationService orderAmounts) {
        this(orderItemRepository, catalogItemRepository, settlementService, orderRepository, inventoryService,
                productRepository, orderAmounts, AuditClient.disabled(), null, null);
    }

    /** 兼容既有 Web 层测试（含审计，不含消费者会员查询）。 */
    public OrderItemController(OrderItemRepository orderItemRepository,
                               CatalogItemRepository catalogItemRepository,
                               SettlementApplicationService settlementService, OrderRepository orderRepository,
                               InventoryApplicationService inventoryService, ProductRepository productRepository,
                               OrderAmountApplicationService orderAmounts, AuditClient auditClient) {
        this(orderItemRepository, catalogItemRepository, settlementService, orderRepository, inventoryService,
                productRepository, orderAmounts, auditClient, null, null);
    }

    /**
     * 写路径后的提醒失效（兼容装配下无该服务，按空操作）。
     *
     * <p><b>曾经的线上故障（2026-09-19）</b>：这里误写成调用自身（`invalidatePendingApproval()`），
     * 于是「客户提交加项 / 确认 / 拒绝」三条写路径都变成无条件无限递归 → {@code StackOverflowError}
     * → Spring 把它包成 {@code ServletException} 交给兜底 advice → 前端拿到 500
     * `INTERNAL_ERROR 服务内部错误，请稍后重试`。此前单测没发现，是因为测试装配一律把
     * {@code pendingApprovalService} 传 null，null 分支直接返回、递归根本进不去；只有生产
     * （Spring 注入真实 Bean）才会命中。因此回归守卫必须用**非 null 的真实 Bean**装配
     * （见 {@code OrderItemControllerPendingApprovalTest}）。
     */
    private void invalidatePendingApproval() {
        if (pendingApprovalService != null) {
            pendingApprovalService.invalidateCurrentStore();
        }
    }

    /**
     * 加项（加钟/加商品/加服务）。
     * 传入 catalogItemId 时，服务端按目录项快照名称/单价/单位（防止客户端篡改价格）；
     * catalogItemId 为必填，名称/单价必须来自服务端目录快照。
     *
     * <p>授权分两条路径（见 {@link #requireAddItem}）：商户持 {@code order.add_item} 按原样加项；
     * A380 C 端消费者会话只被授予 reservation.view/reservation.create，走「本人订单 + 强制
     * source=CUSTOMER」的自助路径，提交后为 PENDING_APPROVAL，由服务人员确认才生效。
     */
    @PostMapping("/items")
    @Transactional
    public OrderItemView addItem(@PathVariable Long orderId, @RequestBody AddItemRequest req) {
        AddItemRequest effective = requireAddItem(orderId, req);
        try {
            return doAddItem(orderId, effective);
        } catch (RuntimeException failure) {
            // 加项失败留痕（订单终态/数量非法/目录项越权/库存不足/落库失败）：action 与成功路径同码。
            recordFailure("order.item.add", orderId, null, null, failure);
            throw failure;
        }
    }

    private OrderItemView doAddItem(Long orderId, AddItemRequest req) {
        Order order = requireOrder(orderId);
        // 终态订单不可再加项：COMPLETED/VOIDED/CANCELLED 的金额快照与已收金额已经固化，
        // 允许加项会把「已收款订单」的合计改写成新值（账单凭空出现应收），不再允许。
        if (isTerminalStatus(order.status())) {
            throw new BusinessException("ORDER_STATUS_INVALID", "已完成/已作废/已取消订单不可加项");
        }
        if (req.quantity() == null || req.quantity().signum() <= 0 || req.quantity().scale() > 3) {
            throw new BusinessException("ORDER_ITEM_INVALID", "数量必须为正数且最多三位小数");
        }
        Long tenantId = TenantContextHolder.tenantIdOrNull();
        if (tenantId == null) { throw new ApiException(401, "TENANT_CONTEXT_MISSING", "缺少租户上下文"); }

        // 服务端价目快照：按目录项回填名称/单价，忽略客户端传入的 name/unitPrice。
        String name = req.name();
        BigDecimal unitPrice = req.unitPrice();
        String itemType = req.itemType();
        CatalogItem catalog;
        Long catalogItemId = null;
        if (req.catalogItemId() != null) {
            catalog = catalogItemRepository.findById(req.catalogItemId())
                    .orElseThrow(() -> new BusinessException("CATALOG_ITEM_INVALID", "目录项不存在或已停用"));
            if (!catalog.active()) {
                throw new BusinessException("CATALOG_ITEM_INVALID", "目录项不存在或已停用");
            }
            Long orderStoreId = order.storeId();
            Long operatorStoreId = currentStoreId();
            Long currentTenantId = TenantContextHolder.tenantIdOrNull();
            if (operatorStoreId != null && !java.util.Objects.equals(operatorStoreId, orderStoreId)) {
                throw new BusinessException("STORE_SCOPE_DENIED", "无权操作其他门店订单");
            }
            if (!java.util.Objects.equals(catalog.tenantId(), currentTenantId)
                    || (catalog.storeId() != null && !java.util.Objects.equals(catalog.storeId(), orderStoreId))) {
                throw new BusinessException("CATALOG_SCOPE_DENIED", "无权使用其他门店目录项");
            }
            name = catalog.name();
            unitPrice = catalog.unitPrice();
            itemType = catalog.itemType();
            catalogItemId = catalog.id();
        } else {
            throw new BusinessException("CATALOG_ITEM_REQUIRED", "加项必须选择有效目录项");
        }

        if (unitPrice == null || unitPrice.signum() < 0 || name == null || name.isBlank()) {
            throw new BusinessException("ORDER_ITEM_INVALID", "加项必须关联有效目录项");
        }

        // 加项来源：CUSTOMER(消费者)需服务人员确认(PENDING_APPROVAL)，MERCHANT(商户/服务员)直接生效(ACTIVE)。
        String source = req.source() == null || req.source().isBlank() ? "MERCHANT" : req.source();
        // 是否占库存一律以「商品 + 物料」为准（目录项 stock_controlled 只作兜底缓存，杜绝两套口径）。
        Long inventoryMaterialId = resolveInventoryMaterial(catalog, order);
        boolean stockControlled = inventoryMaterialId != null;
        // 领域模型不可变：直接构造待落库明细（金额/税额 0、库存状态 NOT_APPLICABLE、其余快照列见下）。
        OrderItem item = new OrderItem(
                null,
                tenantId,
                order.storeId(),
                order.businessType(),
                orderId,
                itemType == null || itemType.isBlank() ? "ADD_ON" : itemType,
                catalogItemId,
                req.resourceId(),
                name,
                unitPrice,
                req.quantity(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                unitPrice.multiply(req.quantity()),
                // 币种快照（16_CURRENCY_CONVENTIONS §5）：明细继承其所属订单的币种快照，
                // 保证「订单 ↔ 明细」永不出现两种币种；订单币种为空（历史/异常数据）时回退默认 USD。
                Currency.parse(order.currencyCode()).code(),
                null,
                "CUSTOMER".equals(source) ? OrderItem.STATUS_PENDING_APPROVAL : OrderItem.STATUS_ACTIVE,
                source,
                "NOT_APPLICABLE",
                stockControlled ? inventoryMaterialId : null,
                null,
                null,
                null,
                null);
        OrderItem saved = orderItemRepository.insert(item);
        if (stockControlled && saved.active()) {
            saved = consumeInventory(saved);
            orderItemRepository.update(saved);
        }
        // 明细变了就同步订单金额快照，保证订单列表/收银应付与账单口径一致。
        recalculateOrderAmounts(orderId);
        // 客户自助加项落 PENDING_APPROVAL：立即失效「待确认加项」聚合缓存，让后台角标/卡片标记秒级出现
        // （客户提交后门店必须马上知道，不能等 TTL）。
        invalidatePendingApproval();
        // 加项/加钟/加服务：改金额快照，必须留操作日志（含服务端目录项快照与库存占用结果）。
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action("order.item.add")
                .resourceType("ord_order_item").resourceId(String.valueOf(saved.id()))
                .resourceName(saved.nameSnapshot())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson("{\"orderId\":" + orderId + ",\"catalogItemId\":" + saved.catalogItemId()
                        + ",\"itemType\":\"" + saved.itemType() + "\",\"quantity\":" + saved.quantity()
                        + ",\"unitPrice\":" + saved.unitPrice() + ",\"totalAmount\":" + saved.totalAmount()
                        + ",\"status\":\"" + saved.status() + "\",\"inventoryMaterialId\":"
                        + saved.inventoryMaterialId() + "}")
                .build());
        return OrderItemView.from(saved);
    }

    /**
     * 订单加项列表（含状态，B 端查看 C 端提交的待确认加项；C 端消费者查看本人加项）。
     *
     * <p><b>本次修复的根因</b>：旧实现无条件 {@code PermissionGuard.require("order.view")}。
     * 该权限码只授予 B 端角色（V22 授予 tenant.owner/store.manager/store.cashier/store.finance），
     * 而 A380 C 端消费者会话的权限快照来自 {@code iam_consumer_application.permissions_json}，
     * 只有 {@code reservation.view} / {@code reservation.create}（V14 种子）。于是 C 端「加服务项」
     * 页面的第一个 await 就拿到 403 PERMISSION_DENIED，异常冒泡出 async 渲染函数，
     * 页面永远停在「加载中…」。
     *
     * <p>现在按「商户权限 或 本人订单」授权：持 {@code order.view} 的会话行为完全不变；
     * 未持该权限码的会话只能看**本人**订单（account_id → cst_member → ord_order.customer_id，
     * 与 {@code GET /me/orders} 同一份会员口径），他人订单一律 403，不放开跨会员读取。
     */
    @GetMapping("/items")
    public List<OrderItemView> listItems(@PathVariable Long orderId) {
        if (hasPermission(PERMISSION_VIEW_ORDER)) {
            requireOrder(orderId);
        } else {
            requireOwnOrder(orderId, PERMISSION_VIEW_ORDER);
        }
        return orderItemRepository.list(OrderItemQuery.ofOrder(orderId)).stream()
                .map(row -> OrderItemView.from(row.aggregate()))
                .toList();
    }

    /** 当前签名上下文是否持有权限码（消费端会话的权限同样来自签名 token，客户端无法伪造）。 */
    private static boolean hasPermission(String permissionCode) {
        TenantContext context = TenantContextHolder.get();
        return context != null && context.permissions() != null
                && context.permissions().contains(permissionCode);
    }

    /**
     * 消费者自助加项的授权与口径收敛。
     *
     * <ul>
     *   <li>持有 {@code order.add_item}（服务人员/收银员/店长）：请求原样透传，可声明
     *       {@code source=MERCHANT} 直接生效——既有 B 端行为不变。</li>
     *   <li>未持有（A380 C 端消费者会话）：只允许对**本人**订单加项，并把 source 强制成
     *       {@code CUSTOMER}（PENDING_APPROVAL，待服务人员确认）。若原样透传客户端声明的 source，
     *       消费者只要写 {@code source=MERCHANT} 就能绕过服务人员确认直接生效。</li>
     * </ul>
     */
    private AddItemRequest requireAddItem(Long orderId, AddItemRequest req) {
        if (hasPermission(PERMISSION_ADD_ITEM)) {
            return req;
        }
        requireOwnOrder(orderId, PERMISSION_ADD_ITEM);
        return new AddItemRequest(req.tenantId(), req.itemType(), req.catalogItemId(), req.resourceId(),
                req.name(), req.unitPrice(), req.quantity(), SOURCE_CUSTOMER);
    }

    /**
     * 订单归属校验（消费者自助路径）：必须能按签名上下文的 accountId 解析出会员档案，
     * 且订单的 customer_id 就是该会员。
     *
     * <p>解析不出会员（既没有商户权限、也不是会员）时保持**既有** 403 PERMISSION_DENIED 文案，
     * 不新增可探测的信息面；解析出会员但订单不是他的，回 403 {@code ORDER_SCOPE_DENIED}。
     */
    private Order requireOwnOrder(Long orderId, String merchantPermissionCode) {
        Order order = requireOrder(orderId);
        TenantContext context = TenantContextHolder.get();
        Long memberId = context == null || memberLookupProvider == null
                ? null
                : memberLookupProvider.findMemberId(context.tenantId(), context.accountId()).orElse(null);
        if (memberId == null) {
            throw new ApiException(403, "PERMISSION_DENIED", "缺少权限: " + merchantPermissionCode);
        }
        if (order.customerId() == null || !memberId.equals(order.customerId())) {
            throw new ApiException(403, "ORDER_SCOPE_DENIED", "只能查看和操作本人订单");
        }
        return order;
    }

    /**
     * 确认加项：服务人员确认 C 端提交的待确认加项，PENDING_APPROVAL → ACTIVE。
     *
     * <p><b>并发</b>：状态流转走 {@link OrderItemRepository#markApproval} 的**原子条件更新**
     * （{@code WHERE status='PENDING_APPROVAL'}）。两端同时点确认/一端确认一端拒绝时只有一个赢家，
     * 输家拿到 409 {@code ORDER_ITEM_STATUS_INVALID}；重复点确认（已经是 ACTIVE）幂等返回该明细，
     * 不再重复扣库存/重复留痕。此前「先查再 updateById」存在双写与「确认后又被拒绝」的竞态。
     *
     * <p><b>实时性</b>：成功后失效「待确认加项」聚合缓存（角标/卡片标记立即更新）。
     */
    @PostMapping("/items/{itemId}/confirm")
    @Transactional
    public OrderItemView confirm(@PathVariable Long orderId, @PathVariable Long itemId) {
        PermissionGuard.require("order.add_item");
        try {
            OrderItem existing = requireItem(orderId, itemId);
            if (existing.active()) {
                // 幂等：上一次确认已生效，重复点击直接回放（不重复扣库存、不重复留痕）。
                return OrderItemView.from(existing);
            }
            if (!existing.pendingApproval()) {
                throw new BusinessException("ORDER_ITEM_STATUS_INVALID", "仅待确认加项可确认");
            }
            Long tenantId = TenantContextHolder.tenantIdOrNull();
            if (!orderItemRepository.markApproval(tenantId, orderId, itemId, OrderItem.STATUS_ACTIVE,
                    operatorId())) {
                // 并发输家：另一请求已确认或已拒绝。
                throw new BusinessException("ORDER_ITEM_STATUS_INVALID", "该加项已被处理，请刷新后查看");
            }
            // 状态已由条件更新落库；这里只在本地对象上跟进库存与金额快照（不再多查一次库）。
            OrderItem po = existing.approved();
            if ("NOT_APPLICABLE".equals(po.inventoryStatus()) && po.inventoryMaterialId() != null) {
                po = consumeInventory(po);
            }
            // 落库 inventory_status=CONSUMED（条件更新只改了状态/操作人/时间）。
            orderItemRepository.update(po);
            // 待确认加项转正式生效后金额才计入，需同步订单金额快照。
            recalculateOrderAmounts(orderId);
            // 角标/卡片标记的数据源（待确认加项聚合）写后失效：本实例立即更新，多实例最坏等一个 TTL。
            invalidatePendingApproval();
            // 确认加项直接把 C 端提交的待确认项变成应收，必须留痕（此前成功/失败都没有）。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action("order.item.confirm")
                    .actionLabel("订单加项确认")
                    .resourceType("ord_order_item").resourceId(String.valueOf(itemId))
                    .resourceName(po.nameSnapshot())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"orderId\":" + orderId + ",\"status\":\"ACTIVE\"}")
                    .build());
            return OrderItemView.from(po);
        } catch (RuntimeException failure) {
            recordFailure("order.item.confirm", orderId, itemId, null, failure);
            throw failure;
        }
    }

    /** 当前操作人（审计与 updated_by）：无上下文时 0（系统）。accountId() 是原始 long，无需判空。 */
    private Long operatorId() {
        TenantContext context = TenantContextHolder.get();
        return context == null ? 0L : context.accountId();
    }

    /** 明细变更后同步订单金额快照（测试装配可能不注入该服务，缺失时跳过）。 */
    private void recalculateOrderAmounts(Long orderId) {
        if (orderAmounts != null) {
            orderAmounts.recalculate(orderId);
        }
    }

    /**
     * 加项是否占库存、要扣哪个物料。
     *
     * <p>口径（F3）：以「商品 + 其关联物料」为准；{@code ord_catalog_item.stock_controlled}
     * 只是查询缓存/兜底（存量数据可能尚未回填），因此这里先按 catalogItemId 反查商品，
     * 商品说扣就扣、商品说不扣就不扣，避免出现「商品是实物、目录项却标 0 导致不扣库存」的超卖。
     *
     * <p><b>服务不扣库存</b>（第 4 点）：商品类型（或没有关联商品时目录项自身）是 SERVICE 时一律返回 null
     * ——服务是人员的服务，没有物料可扣，也不校验库存；即使目录项缓存列被误标为 1 也不按库存处理。
     * 反过来，只要关联到的商品是实物（PRODUCT），商品就是权威口径，仍按物料扣减。
     *
     * @return 需要扣减的物料 id；返回 null 表示该加项不占库存
     */
    private Long resolveInventoryMaterial(CatalogItem catalog, Order order) {
        Product product = resolveLinkedProduct(catalog, order);
        if (product != null) {
            if (ITEM_TYPE_SERVICE.equalsIgnoreCase(text(product.itemType()))) {
                return null;
            }
        } else if (ITEM_TYPE_SERVICE.equalsIgnoreCase(text(catalog.itemType()))) {
            return null;
        }
        boolean stockControlled = product != null
                ? Boolean.TRUE.equals(product.stockControlled())
                : Boolean.TRUE.equals(catalog.stockControlled());
        if (!stockControlled) {
            return null;
        }
        if (product == null) {
            throw new BusinessException("PRODUCT_NOT_AVAILABLE", "库存商品未关联有效商品，无法扣减库存");
        }
        if (!"ON_SHELF".equals(product.status())) {
            throw new BusinessException("PRODUCT_NOT_AVAILABLE", "库存商品未上架");
        }
        if (product.materialId() == null) {
            throw new BusinessException("PRODUCT_NOT_AVAILABLE", "库存商品未关联仓库商品");
        }
        return product.materialId();
    }

    /** 服务型商品的 item_type 取值（与 ProductApplicationService 的稳定枚举一致）。 */
    private static final String ITEM_TYPE_SERVICE = "SERVICE";

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /** 按目录项反查关联商品（租户 + 订单门店口径；测试装配未注入商品端口时返回 null 走兜底）。 */
    private Product resolveLinkedProduct(CatalogItem catalog, Order order) {
        if (productRepository == null || catalog == null || catalog.id() == null) {
            return null;
        }
        Long storeId = order.storeId() != null ? order.storeId() : currentStoreId();
        return productRepository.find(ProductRepository.ProductQuery.linkedToCatalogItem(
                catalog.tenantId(), storeId, catalog.id())).stream().findFirst().orElse(null);
    }

    /**
     * 扣减加项库存：走 {@link InventoryApplicationService#changeStock} 的原子条件扣减，
     * 库存不足直接抛 {@code INVENTORY_INSUFFICIENT}（HTTP 409），本方法所在事务随之回滚，
     * 不会留下「已扣到负数」或「明细已写但库存没扣」的中间态。
     */
    private OrderItem consumeInventory(OrderItem item) {
        inventoryService.changeStock(item.inventoryMaterialId(), item.quantity(), "CONSUME", "ORDER_ITEM",
                String.valueOf(item.id()), "订单加项扣减", "order-item:" + item.id() + ":consume");
        return item.withInventoryStatus("CONSUMED");
    }

    private Long currentStoreId() {
        TenantContext context = TenantContextHolder.get();
        return context == null ? null : context.storeId();
    }

    /** 终态订单：金额与已收已固化，不允许再加项（null 安全，历史数据可能没有状态）。 */
    private static boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "VOIDED".equals(status) || "CANCELLED".equals(status);
    }

    /**
     * 拒绝加项：服务人员拒绝 C 端提交的待确认加项，PENDING_APPROVAL → REJECTED。
     *
     * <p>与确认同款**原子条件更新**（并发下「确认 vs 拒绝」只有一个赢家），并在成功后失效待确认聚合缓存。
     * 已经是 REJECTED 的重复拒绝幂等返回（不重复留痕）。
     */
    @PostMapping("/items/{itemId}/reject")
    public OrderItemView reject(@PathVariable Long orderId, @PathVariable Long itemId) {
        PermissionGuard.require("order.add_item");
        try {
            OrderItem existing = requireItem(orderId, itemId);
            if (OrderItem.STATUS_REJECTED.equals(existing.status())) {
                return OrderItemView.from(existing);
            }
            if (!existing.pendingApproval()) {
                throw new BusinessException("ORDER_ITEM_STATUS_INVALID", "仅待确认加项可拒绝");
            }
            Long tenantId = TenantContextHolder.tenantIdOrNull();
            if (!orderItemRepository.markApproval(tenantId, orderId, itemId, OrderItem.STATUS_REJECTED,
                    operatorId())) {
                throw new BusinessException("ORDER_ITEM_STATUS_INVALID", "该加项已被处理，请刷新后查看");
            }
            OrderItem po = existing.rejected();
            invalidatePendingApproval();
            // 驳回同样要让「谁驳回了客人的加项」可回溯（此前成功/失败都没有留痕）。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action("order.item.reject")
                    .actionLabel("订单加项驳回")
                    .resourceType("ord_order_item").resourceId(String.valueOf(itemId))
                    .resourceName(po.nameSnapshot())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"orderId\":" + orderId + ",\"status\":\"REJECTED\"}")
                    .build());
            return OrderItemView.from(po);
        } catch (RuntimeException failure) {
            recordFailure("order.item.reject", orderId, itemId, null, failure);
            throw failure;
        }
    }

    /**
     * 加项写操作失败留痕：action 与成功路径同码，{@code result=FAILURE} + 稳定 errorCode。
     *
     * <p>审计只走 {@link AuditClient#recordAsync}（失败仅 WARN），业务异常原样抛出：留痕不改变业务结果。
     * 不带幂等键（重复失败各自留痕），detail 不含金额/名称等业务内容，只留定位用的订单与明细 ID。
     */
    private void recordFailure(String action, Long orderId, Long itemId, String itemName,
                               RuntimeException failure) {
        TenantContext context = TenantContextHolder.get();
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(context == null ? null : context.tenantId())
                .storeId(context == null ? null : context.storeId())
                .action(action)
                .resourceType("ord_order_item")
                .resourceId(itemId == null ? String.valueOf(orderId) : String.valueOf(itemId))
                .resourceName(itemName)
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"orderId\":" + orderId + ",\"itemId\":" + itemId + "}")
                .build());
    }

    private OrderItem requireItem(Long orderId, Long itemId) {
        requireOrder(orderId);
        OrderItem item = orderItemRepository.findById(itemId).orElse(null);
        if (item == null || !orderId.equals(item.orderId())) {
            throw new BusinessException("ORDER_ITEM_NOT_FOUND", "加项不存在");
        }
        return item;
    }

    private Order requireOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            if (orderRepository.findTenantIdById(orderId).isPresent()) {
                throw new ApiException(403, "TENANT_SCOPE_DENIED", "无权访问其他租户的订单");
            }
            throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        }
        return order;
    }

    /** 结算：服务端汇总明细固化金额快照。 */
    @PostMapping("/settle")
    public OrderView settle(@PathVariable Long orderId, @RequestBody SettleRequest req) {
        PermissionGuard.require("order.settle");
        return OrderView.from(settlementService.settle(orderId, req.expectedVersion()));
    }

    public record AddItemRequest(Long tenantId, String itemType, Long catalogItemId, Long resourceId,
                                 String name, BigDecimal unitPrice, BigDecimal quantity, String source) {}
    public record SettleRequest(int expectedVersion) {}
}
