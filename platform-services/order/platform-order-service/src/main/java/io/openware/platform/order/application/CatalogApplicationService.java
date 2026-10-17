package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.dto.CatalogItemView;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.model.Product;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.AuditSnapshot;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.Persisted;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.domain.inventory.model.InventoryMaterial;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品/服务目录的读写编排：列表可点性/图片兜底/库存可用量，以及目录项的新建、改价、软删。
 *
 * <p>控制器只做协议转换（standards/10 §应用服务规则）；视图字段集与原持久化对象一致（JSON 契约不变），
 * 三个**非列**字段（available / unavailableReason / availableQuantity）只存在于视图里。
 *
 * <p>物料图片兜底走库存子域的 {@link InventoryMaterialRepository}（窄用法：只 {@code findById}）：
 * 目录项自身没图、商品也没图时，按「商品 → 关联物料」读一次物料的两列图片字段。
 *
 * <p>可点性口径（F3）：库存控制与售罄判断一律以「商品 + 其关联物料」为准，
 * {@code ord_catalog_item.stock_controlled} 只作为查询缓存/兜底（存量数据可能尚未回填）。
 */
@Service
public class CatalogApplicationService {

  /** 审计资源类型与动作码：与 {@code AuditActions} 的稳定码一致（前端按码筛选）。 */
  private static final String AUDIT_RESOURCE_TYPE = "ord_catalog_item";
  private static final String ACTION_CREATE = "catalog.item.create";
  private static final String ACTION_UPDATE = "catalog.item.update";
  private static final String ACTION_DELETE = "catalog.item.delete";

  private final CatalogItemRepository catalogItemRepository;
  private final ProductRepository productRepository;
  private final InventoryMaterialRepository materialRepository;
  private final InventoryApplicationService inventoryService;
  private final AuditClient auditClient;

  /** 兼容既有单测/装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
  public CatalogApplicationService(CatalogItemRepository catalogItemRepository,
                                   ProductRepository productRepository,
                                   InventoryMaterialRepository materialRepository,
                                   InventoryApplicationService inventoryService) {
    this(catalogItemRepository, productRepository, materialRepository, inventoryService, AuditClient.disabled());
  }

  @Autowired
  public CatalogApplicationService(CatalogItemRepository catalogItemRepository,
                                   ProductRepository productRepository,
                                   InventoryMaterialRepository materialRepository,
                                   InventoryApplicationService inventoryService,
                                   AuditClient auditClient) {
    this.catalogItemRepository = catalogItemRepository;
    this.productRepository = productRepository;
    this.materialRepository = materialRepository;
    this.inventoryService = inventoryService;
    this.auditClient = auditClient;
  }

  /**
   * 目录列表（按分类 + 门店；仅 ACTIVE）。只展示关联了已上架商品的目录项；
   * 服务类目录项不占库存、未关联商品也可点；不可点的项标记 available=false + 原因而不是隐藏。
   */
  @Transactional(readOnly = true)
  public List<CatalogItemView> list(String category, Long storeId) {
    Long tenantId = TenantContextHolder.tenantIdOrNull();
    TenantContext context = TenantContextHolder.get();
    Long currentStoreId = context == null ? null : context.storeId();
    if (tenantId == null) {
      throw new ApiException(401, "TENANT_CONTEXT_MISSING", "缺少租户上下文");
    }
    if (storeId != null && currentStoreId != null && !currentStoreId.equals(storeId)) {
      throw new ApiException(403, "STORE_SCOPE_DENIED", "无权访问其他门店目录");
    }
    // 门店口径：显式 storeId 优先，其次签名上下文里的门店；两者都为空即只看租户级通用。
    Long scopedStoreId = storeId != null ? storeId : currentStoreId;
    List<CatalogItem> items = catalogItemRepository.find(
        CatalogItemRepository.CatalogItemQuery.orderable(tenantId, scopedStoreId, blankToNull(category)));
    Map<Long, Product> products = loadProducts(items);
    List<CatalogItem> orderable = items.stream()
        .filter(item -> isOrderableCatalogItem(item, products))
        .toList();
    Map<Long, Map<Long, BigDecimal>> availability = loadAvailability(tenantId, orderable, products);
    List<CatalogItemView> views = new ArrayList<>(orderable.size());
    for (CatalogItem item : orderable) {
      // 纯服务目录项没有 product_id：不能拿 null 去查 Map（不可变空 Map 会 NPE）。
      Product product = item.productId() == null ? null : products.get(item.productId());
      views.add(decorate(CatalogItemView.from(item, (AuditSnapshot) null), item, product, availability));
    }
    return views;
  }

  /** 新增目录项（B 端商户经营）。 */
  @Transactional
  public CatalogItemView create(CatalogItemCommand req) {
    try {
      Persisted<CatalogItem> persisted = doCreate(req);
      CatalogItemView view = CatalogItemView.from(persisted.aggregate(), persisted.audit());
      recordItemAudit(ACTION_CREATE, view);
      return view;
    } catch (RuntimeException failure) {
      // 失败出口留痕（缺权限/缺租户上下文/名称或价格非法/落库失败）：审计只 WARN，异常原样抛出。
      recordItemFailure(ACTION_CREATE, null, failure);
      throw failure;
    }
  }

  private Persisted<CatalogItem> doCreate(CatalogItemCommand req) {
    PermissionGuard.require("tenant.store.manage");
    Long tenantId = TenantContextHolder.tenantIdOrNull();
    if (tenantId == null) {
      throw new ApiException(401, "TENANT_CONTEXT_MISSING", "缺少租户上下文");
    }
    if (req.name() == null || req.name().isBlank()) {
      throw new ApiException(400, "CATALOG_NAME_REQUIRED", "缺少目录项名称");
    }
    if (req.unitPrice() == null || req.unitPrice().signum() <= 0) {
      throw new ApiException(400, "CATALOG_PRICE_INVALID", "单价需大于 0");
    }
    ItemImages.Images images = ItemImages.normalize(req.imageUrls(), req.mainImageUrl(), "CATALOG_INVALID");
    CatalogItem item = CatalogItem.draft(tenantId, req.storeId(),
        blankToDefault(req.category(), "其他"),
        blankToDefault(req.itemType(), CatalogItem.ITEM_TYPE_PRODUCT),
        req.name(),
        blankToDefault(req.unit(), "份"),
        req.unitPrice(), req.description(), images.urls(), images.mainImageUrl(),
        req.sortOrder() == null ? 0 : req.sortOrder());
    // 审计列（created_at/updated_at）由适配器按落库时刻填充，领域模型不承载它们。
    return catalogItemRepository.insert(item);
  }

  /** 更新目录项（B 端商户经营）。 */
  @Transactional
  public CatalogItemView update(Long id, CatalogItemCommand req) {
    try {
      Persisted<CatalogItem> persisted = doUpdate(id, req);
      CatalogItemView view = CatalogItemView.from(persisted.aggregate(), persisted.audit());
      recordItemAudit(ACTION_UPDATE, view);
      return view;
    } catch (RuntimeException failure) {
      // 失败出口留痕（缺权限/目录项不存在/价格非法/落库失败）：审计只 WARN，异常原样抛出。
      recordItemFailure(ACTION_UPDATE, id, failure);
      throw failure;
    }
  }

  private Persisted<CatalogItem> doUpdate(Long id, CatalogItemCommand req) {
    PermissionGuard.require("tenant.store.manage");
    CatalogItem existing = catalogItemRepository.findById(id)
        .orElseThrow(() -> new BusinessException("CATALOG_ITEM_NOT_FOUND", "目录项不存在"));
    if (req.unitPrice() != null && req.unitPrice().signum() <= 0) {
      throw new ApiException(400, "CATALOG_PRICE_INVALID", "单价需大于 0");
    }
    // 部分更新语义：请求未带的字段沿用既有值。图片两列成对更新（ItemImages 保证主图属于列表）。
    ItemImages.Images images = req.imageUrls() == null
        ? new ItemImages.Images(existing.imagesOrEmpty(), existing.mainImageUrl())
        : ItemImages.normalize(req.imageUrls(), req.mainImageUrl(), "CATALOG_INVALID");
    CatalogItem merged = new CatalogItem(existing.id(), existing.productId(), existing.tenantId(),
        existing.storeId(),
        replaceIfPresent(req.category(), existing.category()),
        replaceIfPresent(req.itemType(), existing.itemType()),
        replaceIfPresent(req.name(), existing.name()),
        replaceIfPresent(req.unit(), existing.unit()),
        req.unitPrice() != null ? req.unitPrice() : existing.unitPrice(),
        req.description() != null ? req.description() : existing.description(),
        images.urls(), images.mainImageUrl(), existing.status(), existing.stockControlled(),
        req.sortOrder() != null ? req.sortOrder() : existing.sortOrder());
    return catalogItemRepository.update(merged);
  }

  /** 停用目录项（软删 status → INACTIVE）。 */
  @Transactional
  public void disable(Long id) {
    try {
      recordItemAudit(ACTION_DELETE, CatalogItemView.from(doDisable(id), (AuditSnapshot) null));
    } catch (RuntimeException failure) {
      // 失败出口留痕（缺权限/目录项不存在/落库失败）：审计只 WARN，异常原样抛出。
      recordItemFailure(ACTION_DELETE, id, failure);
      throw failure;
    }
  }

  private CatalogItem doDisable(Long id) {
    PermissionGuard.require("tenant.store.manage");
    CatalogItem existing = catalogItemRepository.findById(id)
        .orElseThrow(() -> new BusinessException("CATALOG_ITEM_NOT_FOUND", "目录项不存在"));
    return catalogItemRepository.update(existing.disabled()).aggregate();
  }

  /**
   * 一次取回目录项关联的商品（不过滤商品状态，供上架过滤与可用性判断共用，避免逐项查库）。
   */
  private Map<Long, Product> loadProducts(List<CatalogItem> items) {
    List<Long> productIds = items.stream().map(CatalogItem::productId).filter(Objects::nonNull).distinct().toList();
    if (productIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, Product> products = new HashMap<>();
    for (Long productId : productIds) {
      productRepository.findById(productId).ifPresent(product -> products.put(product.id(), product));
    }
    return products;
  }

  /** 下单列表准入：服务类目录项自带价格、不占库存，未关联商品也可点；其余必须关联已上架商品。 */
  private static boolean isOrderableCatalogItem(CatalogItem item, Map<Long, Product> products) {
    if (item.productId() == null) {
      return item.serviceItem();
    }
    Product product = products.get(item.productId());
    return product != null && Product.STATUS_ON_SHELF.equals(product.status());
  }

  /** 批量取回「受库存控制的商品」的可用库存，按门店分组一次查询，并走 TTL 缓存。 */
  private Map<Long, Map<Long, BigDecimal>> loadAvailability(Long tenantId, List<CatalogItem> items,
                                                           Map<Long, Product> products) {
    Map<Long, List<Long>> materialIdsByStore = new LinkedHashMap<>();
    for (CatalogItem item : items) {
      // 纯服务目录项（无 product_id）不参与库存计算；也不能用 null 去查不可变 Map。
      if (item.productId() == null) {
        continue;
      }
      Product product = products.get(item.productId());
      if (product == null || !stockControlled(item, product) || product.materialId() == null) {
        continue;
      }
      Long storeId = product.storeId() != null ? product.storeId() : item.storeId();
      if (storeId == null) {
        continue;
      }
      materialIdsByStore.computeIfAbsent(storeId, key -> new ArrayList<>()).add(product.materialId());
    }
    Map<Long, Map<Long, BigDecimal>> availability = new HashMap<>();
    for (Map.Entry<Long, List<Long>> entry : materialIdsByStore.entrySet()) {
      availability.put(entry.getKey(), inventoryService.availableQuantities(tenantId, entry.getKey(), entry.getValue()));
    }
    return availability;
  }

  /** 点单列表展示所需的服务端补充信息：可点状态 + 图片兜底。 */
  private CatalogItemView decorate(CatalogItemView view, CatalogItem item, Product product,
                                   Map<Long, Map<Long, BigDecimal>> availability) {
    CatalogItemView withImages = applyImages(view, product);
    return applyAvailability(withImages, item, product, availability);
  }

  /** 目录项图片兜底：自身有图直接用；否则取关联商品图；商品也无图时取商品关联的物料图。 */
  private CatalogItemView applyImages(CatalogItemView view, Product product) {
    if (!view.imageUrls().isEmpty()) {
      return view;
    }
    if (product == null) {
      return view;
    }
    if (!product.imagesOrEmpty().isEmpty()) {
      return view.withImages(product.imagesOrEmpty(), product.mainImageUrl());
    }
    if (product.materialId() == null || materialRepository == null) {
      return view;
    }
    InventoryMaterial material = materialRepository.findById(product.materialId()).orElse(null);
    if (material == null || material.imagesOrEmpty().isEmpty()) {
      return view;
    }
    return view.withImages(material.imagesOrEmpty(), material.mainImageUrl());
  }

  /** 标记可点状态：非实物商品始终可点；实物商品要求已上架 + 已关联物料 + 可用库存 > 0。 */
  private CatalogItemView applyAvailability(CatalogItemView view, CatalogItem item, Product product,
                                            Map<Long, Map<Long, BigDecimal>> availability) {
    if (product == null) {
      // 服务类目录项不依赖商品与库存：只要有 SERVICE 标记就可点（价格来自目录项自身）。
      if (item.serviceItem()) {
        return view.withAvailability(true, null, false, null);
      }
      return view.withAvailability(false, "未关联商品", view.stockControlled(), null);
    }
    boolean stockControlled = stockControlled(item, product);
    if (!stockControlled) {
      return view.withAvailability(true, null, false, null);
    }
    if (!Product.STATUS_ON_SHELF.equals(product.status())) {
      return view.withAvailability(false, "商品未上架", true, null);
    }
    if (product.materialId() == null) {
      return view.withAvailability(false, "未关联物料", true, null);
    }
    Long storeId = product.storeId() != null ? product.storeId() : view.storeId();
    BigDecimal available = availability.getOrDefault(storeId, Map.of())
        .getOrDefault(product.materialId(), BigDecimal.ZERO);
    boolean inStock = available.signum() > 0;
    // 可用库存数量随响应返回：点单/加项页用它限制「加号」上限；真正扣减仍由 add-item 原子条件扣减兜底。
    return view.withAvailability(inStock, inStock ? null : "已售罄", true, available.max(BigDecimal.ZERO));
  }

  /** 是否扣库存：商品是权威口径；商品未给出该标记时退回目录项缓存列。 */
  private static boolean stockControlled(CatalogItem item, Product product) {
    if (product.stockControlled() != null) {
      return Boolean.TRUE.equals(product.stockControlled());
    }
    return Boolean.TRUE.equals(item.stockControlled());
  }

  /**
   * 目录项写操作成功留痕：动作码与 {@code AuditActions} 登记的一致，幂等键取「动作 + 目录项 ID」。
   * detail 只放数值/枚举字段，运营填写的名称走 {@code resourceName}，避免手工拼 JSON 被破坏。
   */
  private void recordItemAudit(String action, CatalogItemView view) {
    auditClient.recordAsync(AuditClient.AuditRecord.builder()
        .tenantId(view.tenantId())
        .storeId(view.storeId())
        .action(action)
        .resourceType(AUDIT_RESOURCE_TYPE)
        .resourceId(String.valueOf(view.id()))
        .resourceName(view.name())
        .result(AuditClient.AuditRecord.RESULT_SUCCESS)
        .idempotencyKey(action + ":" + view.id())
        .detailJson("{\"storeId\":" + view.storeId() + ",\"unitPrice\":" + view.unitPrice()
            + ",\"status\":\"" + view.status() + "\"}")
        .build());
  }

  /** 目录项写操作失败留痕：动作码与成功路径同码、{@code result=FAILURE} + 稳定错误码。 */
  private void recordItemFailure(String action, Long itemId, RuntimeException failure) {
    TenantContext context = TenantContextHolder.get();
    auditClient.recordAsync(AuditClient.AuditRecord.builder()
        .tenantId(context == null ? null : context.tenantId())
        .storeId(context == null ? null : context.storeId())
        .action(action)
        .resourceType(AUDIT_RESOURCE_TYPE)
        .resourceId(itemId == null ? null : String.valueOf(itemId))
        .result(AuditClient.AuditRecord.RESULT_FAILURE)
        .errorCode(AuditErrorCodes.of(failure))
        .detailJson("{\"itemId\":" + itemId + "}")
        .build());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String blankToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /** 部分更新：请求带了非空值才替换。 */
  private static String replaceIfPresent(String requested, String existing) {
    return requested == null || requested.isBlank() ? existing : requested;
  }

  /** 目录项写命令（与 HTTP 请求解耦，应用服务不依赖控制器 DTO）。 */
  public record CatalogItemCommand(Long storeId, String category, String itemType, String name,
                                   String unit, BigDecimal unitPrice, String description, Integer sortOrder,
                                   List<String> imageUrls, String mainImageUrl) {
  }
}
