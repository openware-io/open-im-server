package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.currency.Currency;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
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
import io.openware.platform.order.domain.order.repository.Persisted;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单加项的读/写编排：列表、加项、确认、拒绝。
 *
 * <p>授权、目录项价目快照、库存占用、订单金额同步、待确认聚合缓存失效与审计都在应用服务完成；
 * 控制器只做协议转换（standards/10 §应用服务规则）。
 *
 * <p><b>授权分两条路径</b>：持 {@code order.add_item} 的商户按原样加项（可声明 source=MERCHANT 直接生效）；
 * A380 C 端消费者会话只被授予 reservation.view/create，走「本人订单 + 强制 source=CUSTOMER」的自助路径，
 * 提交后为 PENDING_APPROVAL，由服务人员确认才生效。
 */
@Service
public class OrderItemApplicationService {

  /** 商户加项权限码：服务人员/收银员可对任意订单加项（可声明 source=MERCHANT 直接生效）。 */
  private static final String PERMISSION_ADD_ITEM = "order.add_item";
  /** 商户查看订单明细权限码：持有它可查看任意订单的加项列表。 */
  private static final String PERMISSION_VIEW_ORDER = "order.view";
  /** 消费者自助加项的来源：PENDING_APPROVAL 待服务人员确认，不计入应收。 */
  private static final String SOURCE_CUSTOMER = "CUSTOMER";
  /** 服务型商品的 item_type 取值（与 ProductApplicationService 的稳定枚举一致）。 */
  private static final String ITEM_TYPE_SERVICE = "SERVICE";

  private final OrderItemRepository orderItemRepository;
  private final CatalogItemRepository catalogItemRepository;
  private final OrderRepository orderRepository;
  private final InventoryApplicationService inventoryService;
  private final ProductRepository productRepository;
  private final OrderAmountApplicationService orderAmounts;
  private final AuditClient auditClient;
  private final MemberLookupProvider memberLookupProvider;
  private final PendingApprovalApplicationService pendingApprovalService;

  @Autowired
  public OrderItemApplicationService(OrderItemRepository orderItemRepository,
                                     CatalogItemRepository catalogItemRepository,
                                     OrderRepository orderRepository,
                                     InventoryApplicationService inventoryService,
                                     ProductRepository productRepository,
                                     OrderAmountApplicationService orderAmounts,
                                     AuditClient auditClient,
                                     MemberLookupProvider memberLookupProvider,
                                     PendingApprovalApplicationService pendingApprovalService) {
    this.orderItemRepository = orderItemRepository;
    this.catalogItemRepository = catalogItemRepository;
    this.orderRepository = orderRepository;
    this.inventoryService = inventoryService;
    this.productRepository = productRepository;
    this.orderAmounts = orderAmounts;
    this.auditClient = auditClient;
    this.memberLookupProvider = memberLookupProvider;
    this.pendingApprovalService = pendingApprovalService;
  }

  /**
   * 订单加项列表（含状态，B 端查看 C 端提交的待确认加项；C 端消费者查看本人加项）。
   *
   * <p>按「商户权限 或 本人订单」授权：持 {@code order.view} 的会话行为不变；
   * 未持该权限码的会话只能看本人订单，他人订单一律 403。
   */
  @Transactional(readOnly = true)
  public List<OrderItem> listItems(Long orderId) {
    if (hasPermission(PERMISSION_VIEW_ORDER)) {
      requireOrder(orderId);
    } else {
      requireOwnOrder(orderId, PERMISSION_VIEW_ORDER);
    }
    return orderItemRepository.list(OrderItemQuery.ofOrder(orderId)).stream()
        .map(Persisted::aggregate)
        .toList();
  }

  /**
   * 加项（加钟/加商品/加服务）。
   * 传入 catalogItemId 时，服务端按目录项快照名称/单价/单位（防止客户端篡改价格）；
   * catalogItemId 为必填，名称/单价必须来自服务端目录快照。
   */
  @Transactional
  public OrderItem addItem(Long orderId, AddItemCommand req) {
    AddItemCommand effective = requireAddItem(orderId, req);
    try {
      return doAddItem(orderId, effective);
    } catch (RuntimeException failure) {
      // 加项失败留痕（订单终态/数量非法/目录项越权/库存不足/落库失败）：action 与成功路径同码。
      recordFailure("order.item.add", orderId, null, null, failure);
      throw failure;
    }
  }

  private OrderItem doAddItem(Long orderId, AddItemCommand req) {
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
    if (tenantId == null) {
      throw new ApiException(401, "TENANT_CONTEXT_MISSING", "缺少租户上下文");
    }

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
      if (operatorStoreId != null && !Objects.equals(operatorStoreId, orderStoreId)) {
        throw new BusinessException("STORE_SCOPE_DENIED", "无权操作其他门店订单");
      }
      if (!Objects.equals(catalog.tenantId(), currentTenantId)
          || (catalog.storeId() != null && !Objects.equals(catalog.storeId(), orderStoreId))) {
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
    return saved;
  }

  /**
   * 确认加项：服务人员确认 C 端提交的待确认加项，PENDING_APPROVAL → ACTIVE。
   *
   * <p>状态流转走 {@link OrderItemRepository#markApproval} 的原子条件更新；
   * 重复点确认（已经是 ACTIVE）幂等返回该明细，不再重复扣库存/重复留痕。
   */
  @Transactional
  public OrderItem confirm(Long orderId, Long itemId) {
    PermissionGuard.require("order.add_item");
    try {
      OrderItem existing = requireItem(orderId, itemId);
      if (existing.active()) {
        // 幂等：上一次确认已生效，重复点击直接回放（不重复扣库存、不重复留痕）。
        return existing;
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
      return po;
    } catch (RuntimeException failure) {
      recordFailure("order.item.confirm", orderId, itemId, null, failure);
      throw failure;
    }
  }

  /**
   * 拒绝加项：服务人员拒绝 C 端提交的待确认加项，PENDING_APPROVAL → REJECTED。
   *
   * <p>与确认同款原子条件更新（并发下「确认 vs 拒绝」只有一个赢家），并在成功后失效待确认聚合缓存。
   * 已经是 REJECTED 的重复拒绝幂等返回（不重复留痕）。
   */
  @Transactional
  public OrderItem reject(Long orderId, Long itemId) {
    PermissionGuard.require("order.add_item");
    try {
      OrderItem existing = requireItem(orderId, itemId);
      if (OrderItem.STATUS_REJECTED.equals(existing.status())) {
        return existing;
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
      return po;
    } catch (RuntimeException failure) {
      recordFailure("order.item.reject", orderId, itemId, null, failure);
      throw failure;
    }
  }

  /** 写路径后的提醒失效（兼容装配下无该服务，按空操作）。 */
  private void invalidatePendingApproval() {
    if (pendingApprovalService != null) {
      pendingApprovalService.invalidateCurrentStore();
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
   * 加项是否占库存、要扣哪个物料。以「商品 + 其关联物料」为准；
   * 服务类商品一律返回 null（服务没有物料可扣）。
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

  /** 加项写操作失败留痕：action 与成功路径同码，{@code result=FAILURE} + 稳定 errorCode。 */
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

  /**
   * 消费者自助加项的授权与口径收敛：持 add_item 的商户原样透传；
   * 未持的 C 端会话只允许本人订单，并强制 source=CUSTOMER。
   */
  private AddItemCommand requireAddItem(Long orderId, AddItemCommand req) {
    if (hasPermission(PERMISSION_ADD_ITEM)) {
      return req;
    }
    requireOwnOrder(orderId, PERMISSION_ADD_ITEM);
    return new AddItemCommand(req.tenantId(), req.itemType(), req.catalogItemId(), req.resourceId(),
        req.name(), req.unitPrice(), req.quantity(), SOURCE_CUSTOMER);
  }

  /** 订单归属校验（消费者自助路径）：accountId → cst_member.id → ord_order.customer_id。 */
  private Order requireOwnOrder(Long orderId, String merchantPermissionCode) {
    Order order = requireOrder(orderId);
    TenantContext context = TenantContextHolder.get();
    Long memberId = context == null || memberLookupProvider == null
        ? null
        : memberLookupProvider.findMemberId(context.tenantId(), context.accountId()).orElse(null);
    if (memberId == null) {
      // 既没有商户权限也不是会员：与其它自助端点保持同一文案（不泄露订单是否存在）。
      throw new ApiException(403, "PERMISSION_DENIED", "缺少权限: " + merchantPermissionCode);
    }
    if (order.customerId() == null || !memberId.equals(order.customerId())) {
      throw new ApiException(403, "ORDER_SCOPE_DENIED", "只能查看和操作本人订单");
    }
    return order;
  }

  /** 当前签名上下文是否持有权限码（消费端/B 端权限都来自签名 token，客户端无法伪造）。 */
  private static boolean hasPermission(String permissionCode) {
    TenantContext context = TenantContextHolder.get();
    return context != null && context.permissions() != null
        && context.permissions().contains(permissionCode);
  }

  /** 订单加项写命令（与 HTTP 请求解耦）。 */
  public record AddItemCommand(Long tenantId, String itemType, Long catalogItemId, Long resourceId,
                               String name, BigDecimal unitPrice, BigDecimal quantity, String source) {
  }
}
