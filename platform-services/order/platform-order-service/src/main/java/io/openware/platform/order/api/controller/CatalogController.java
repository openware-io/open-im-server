package io.openware.platform.order.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.platform.order.application.InventoryApplicationService;
import io.openware.platform.order.application.ItemImages;
import io.openware.platform.order.infra.persistence.mapper.CatalogItemMapper;
import io.openware.platform.order.infra.persistence.mapper.InventoryMaterialMapper;
import io.openware.platform.order.infra.persistence.mapper.ProductMapper;
import io.openware.platform.order.infra.persistence.po.CatalogItemPo;
import io.openware.platform.order.infra.persistence.po.InventoryMaterialPo;
import io.openware.platform.order.infra.persistence.po.ProductPo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 商品/服务目录：B 端点单与 C 端自助加项的统一目录。
 * 列表端点 C 端（business）与 B 端共用；写端点需商户经营权限。
 *
 * <p>可点性口径（F3 修复）：库存控制与售罄判断一律以「商品 + 其关联物料」为准，
 * {@code ord_catalog_item.stock_controlled} 只作为查询缓存/兜底（存量数据可能尚未回填）。
 * 可用库存走 {@link InventoryApplicationService} 的批量 + TTL 缓存读，避免逐项查库。
 *
 * <p>审计（2026-09 补齐）：目录项是点单页价格与分组的来源，新建/改价/删除此前与商品写操作不同，
 * 一条留痕都没有（本服务没有 BFF 那样的全局审计拦截器），后台删掉目录项在操作日志里查不到。
 * 现在成功与失败（缺权限、缺租户上下文、名称/价格非法、项不存在）都上报统一审计。
 */
@RestController
@RequestMapping("/business/catalog")
public class CatalogController {

    /** 审计资源类型与动作码：与 {@code AuditActions} 的稳定码一致（前端按码筛选）。 */
    private static final String AUDIT_RESOURCE_TYPE = "ord_catalog_item";
    private static final String ACTION_CREATE = "catalog.item.create";
    private static final String ACTION_UPDATE = "catalog.item.update";
    private static final String ACTION_DELETE = "catalog.item.delete";

    private final CatalogItemMapper catalogItemMapper;
    private final ProductMapper productMapper;
    private final InventoryMaterialMapper materialMapper;
    private final InventoryApplicationService inventoryService;
    private final AuditClient auditClient;

    @Autowired
    public CatalogController(CatalogItemMapper catalogItemMapper, ProductMapper productMapper,
                             InventoryMaterialMapper materialMapper, InventoryApplicationService inventoryService,
                             AuditClient auditClient) {
        this.catalogItemMapper = catalogItemMapper;
        this.productMapper = productMapper;
        this.materialMapper = materialMapper;
        this.inventoryService = inventoryService;
        this.auditClient = auditClient;
    }

    /** 兼容既有单测装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
    public CatalogController(CatalogItemMapper catalogItemMapper, ProductMapper productMapper,
                             InventoryMaterialMapper materialMapper, InventoryApplicationService inventoryService) {
        this(catalogItemMapper, productMapper, materialMapper, inventoryService, AuditClient.disabled());
    }

    /**
     * 目录列表（按分类 + 门店；仅 ACTIVE）。
     * 下单列表的数据源只展示「商品管理里已上架」的商品：目录项必须关联商品，且该商品 status=ON_SHELF。
     * 没关联商品的目录项（历史种子数据、直接建的纯目录项）不再出现在点单列表。
     * 不可点的项（未关联物料 / 售罄）不隐藏，而是标记 available=false + 原因，
     * 让点单页能显示「售罄」而不是让运营以为商品不存在。
     *
     * <p><b>服务类目录项的例外（第 4 点「加项能对服务加项」）</b>：{@code item_type=SERVICE} 的目录项
     * 不占库存、单价来自目录自身（服务不依赖物料），因此**未关联商品也可点**——它与「服务人员点单」
     * （{@code POST /business/orders/{id}/servers}，按 ktv 计价方案按时长计费）共用同一份
     * {@code item_type=SERVICE} 目录项，这里只是让同一份目录项也能按目录单价直接加项，不造第二套。
     * PRODUCT/PACKAGE/ADD_ON 类仍要求关联已上架商品，守住「实物商品必须走商品+库存口径」的红线。
     */
    @GetMapping("/items")
    public List<CatalogItemPo> list(@RequestParam(required = false) String category,
                                    @RequestParam(required = false) Long storeId) {
        Long tenantId = TenantContextHolder.tenantIdOrNull();
        TenantContext context = TenantContextHolder.get();
        Long currentStoreId = context == null ? null : context.storeId();
        if (tenantId == null) {
            throw new ApiException(401, "TENANT_CONTEXT_MISSING", "缺少租户上下文");
        }
        LambdaQueryWrapper<CatalogItemPo> qw = new LambdaQueryWrapper<CatalogItemPo>()
                .eq(CatalogItemPo::getTenantId, tenantId)
                .eq(CatalogItemPo::getStatus, "ACTIVE")
                .orderByAsc(CatalogItemPo::getSortOrder)
                .orderByAsc(CatalogItemPo::getId);
        if (category != null && !category.isBlank()) {
            qw.eq(CatalogItemPo::getCategory, category);
        }
        if (storeId != null) {
            if (currentStoreId != null && !currentStoreId.equals(storeId)) {
                throw new ApiException(403, "STORE_SCOPE_DENIED", "无权访问其他门店目录");
            }
            qw.and(w -> w.eq(CatalogItemPo::getStoreId, storeId).or().isNull(CatalogItemPo::getStoreId));
        } else if (currentStoreId != null) {
            qw.and(w -> w.eq(CatalogItemPo::getStoreId, currentStoreId).or().isNull(CatalogItemPo::getStoreId));
        }
        List<CatalogItemPo> items = catalogItemMapper.selectList(qw);
        Map<Long, ProductPo> products = loadProducts(items);
        List<CatalogItemPo> orderable = items.stream()
                .filter(item -> isOrderableCatalogItem(item, products))
                .toList();
        Map<Long, Map<Long, BigDecimal>> availability = loadAvailability(tenantId, orderable, products);
        // 纯服务目录项没有 product_id：不能拿 null 去查 Map（不可变空 Map 会 NPE）。
        orderable.forEach(item -> decorate(item,
                item.getProductId() == null ? null : products.get(item.getProductId()), availability));
        return orderable;
    }

    /** 一次批量取回目录项关联的商品（不过滤商品状态，供上架过滤与可用性判断共用，避免逐项查库）。 */
    private Map<Long, ProductPo> loadProducts(List<CatalogItemPo> items) {
        List<Long> productIds = items.stream().map(CatalogItemPo::getProductId).filter(Objects::nonNull).distinct().toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productMapper.selectByIds(productIds).stream()
                .collect(Collectors.toMap(ProductPo::getId, product -> product));
    }

    /**
     * 下单列表准入：服务类目录项自带价格、不占库存，未关联商品也可点；
     * 其余类型（PRODUCT/PACKAGE/ADD_ON）必须关联了商品，且该商品在商品管理里已上架。
     */
    private static boolean isOrderableCatalogItem(CatalogItemPo item, Map<Long, ProductPo> products) {
        if (item.getProductId() == null) {
            return isServiceItem(item);
        }
        ProductPo product = products.get(item.getProductId());
        return product != null && "ON_SHELF".equals(product.getStatus());
    }

    /** 是否服务类目录项（item_type=SERVICE）。 */
    private static boolean isServiceItem(CatalogItemPo item) {
        String itemType = item == null ? null : item.getItemType();
        return "SERVICE".equalsIgnoreCase(itemType == null ? "" : itemType.trim());
    }

    /**
     * 批量取回「受库存控制的商品」的可用库存，按门店分组一次查询（不是逐项查库），并走 TTL 缓存。
     * 返回结构：storeId → (materialId → 可用库存)。
     */
    private Map<Long, Map<Long, BigDecimal>> loadAvailability(Long tenantId, List<CatalogItemPo> items,
                                                             Map<Long, ProductPo> products) {
        Map<Long, List<Long>> materialIdsByStore = new LinkedHashMap<>();
        for (CatalogItemPo item : items) {
            // 纯服务目录项（无 product_id）不参与库存计算；也不能用 null 去查不可变 Map。
            if (item.getProductId() == null) {
                continue;
            }
            ProductPo product = products.get(item.getProductId());
            if (product == null || !stockControlled(item, product) || product.getMaterialId() == null) {
                continue;
            }
            Long storeId = product.getStoreId() != null ? product.getStoreId() : item.getStoreId();
            if (storeId == null) {
                continue;
            }
            materialIdsByStore.computeIfAbsent(storeId, key -> new ArrayList<>()).add(product.getMaterialId());
        }
        Map<Long, Map<Long, BigDecimal>> availability = new HashMap<>();
        for (Map.Entry<Long, List<Long>> entry : materialIdsByStore.entrySet()) {
            availability.put(entry.getKey(), inventoryService.availableQuantities(tenantId, entry.getKey(), entry.getValue()));
        }
        return availability;
    }

    /**
     * 点单列表展示所需的服务端补充信息：
     * 1) 可点状态（未关联物料 / 售罄 → available=false + 原因，前端置灰展示而不是隐藏）；
     * 2) 图片兜底：目录项自身没图时，按「关联商品 → 商品关联物料」取一次并只在本响应里补上，
     *    这样历史数据（V14 之前建的目录项）也不需要等回填就能看到图。
     */
    private void decorate(CatalogItemPo item, ProductPo product, Map<Long, Map<Long, BigDecimal>> availability) {
        applyImages(item, product);
        applyAvailability(item, product, availability);
    }

    /** 目录项图片兜底：自身有图直接用；否则取关联商品图；商品也无图时取商品关联的物料图。 */
    private void applyImages(CatalogItemPo item, ProductPo product) {
        if (item.getImageUrls() != null && !item.getImageUrls().isEmpty()) return;
        if (product == null) return;
        if (product.getImageUrls() != null && !product.getImageUrls().isEmpty()) {
            item.setImageUrls(product.getImageUrls());
            item.setMainImageUrl(product.getMainImageUrl());
            return;
        }
        if (product.getMaterialId() == null || materialMapper == null) return;
        InventoryMaterialPo material = materialMapper.selectById(product.getMaterialId());
        if (material == null || material.getImageUrls() == null || material.getImageUrls().isEmpty()) return;
        item.setImageUrls(material.getImageUrls());
        item.setMainImageUrl(material.getMainImageUrl());
    }

    /**
     * 标记可点状态：非实物商品始终可点；实物商品要求已上架 + 已关联仓库商品 + 可用库存 > 0。
     * 是否受库存控制以商品为准（目录项列只作兜底缓存），并把权威值回写到响应里，
     * 这样即便目录项缓存列还没回填（V17 之前的历史数据）也不会漏判售罄。
     */
    private void applyAvailability(CatalogItemPo item, ProductPo product, Map<Long, Map<Long, BigDecimal>> availability) {
        if (product == null) {
            // 服务类目录项不依赖商品与库存：只要有 SERVICE 标记就可点（价格来自目录项自身）。
            if (isServiceItem(item)) {
                item.setStockControlled(false);
                item.setAvailable(true);
                return;
            }
            item.setAvailable(false);
            item.setUnavailableReason("未关联商品");
            return;
        }
        boolean stockControlled = stockControlled(item, product);
        item.setStockControlled(stockControlled);
        if (!stockControlled) {
            item.setAvailable(true);
            return;
        }
        if (!"ON_SHELF".equals(product.getStatus())) {
            item.setAvailable(false);
            item.setUnavailableReason("商品未上架");
            return;
        }
        if (product.getMaterialId() == null) {
            item.setAvailable(false);
            item.setUnavailableReason("未关联物料");
            return;
        }
        Long storeId = product.getStoreId() != null ? product.getStoreId() : item.getStoreId();
        BigDecimal available = availability.getOrDefault(storeId, Map.of())
                .getOrDefault(product.getMaterialId(), BigDecimal.ZERO);
        boolean inStock = available.signum() > 0;
        item.setAvailable(inStock);
        // 可用库存数量随响应返回：点单/加项页用它限制「加号」上限（提交前的防超卖），
        // 真正的扣减仍由 add-item 的原子条件扣减兜底（INVENTORY_INSUFFICIENT 409）。
        item.setAvailableQuantity(available.max(BigDecimal.ZERO));
        if (!inStock) {
            item.setUnavailableReason("已售罄");
        }
    }

    /** 是否扣库存：商品是权威口径；商品未给出该标记时退回目录项缓存列。 */
    private static boolean stockControlled(CatalogItemPo item, ProductPo product) {
        if (product.getStockControlled() != null) {
            return Boolean.TRUE.equals(product.getStockControlled());
        }
        return Boolean.TRUE.equals(item.getStockControlled());
    }

    /** 新增目录项（B 端商户经营）。 */
    @PostMapping("/items")
    public CatalogItemPo create(@RequestBody CatalogItemRequest req) {
        try {
            CatalogItemPo po = doCreate(req);
            recordItemAudit(ACTION_CREATE, po);
            return po;
        } catch (RuntimeException failure) {
            // 失败出口留痕（缺权限/缺租户上下文/名称或价格非法/落库失败）：审计只 WARN，异常原样抛出。
            recordItemFailure(ACTION_CREATE, null, failure);
            throw failure;
        }
    }

    private CatalogItemPo doCreate(CatalogItemRequest req) {
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
        CatalogItemPo po = new CatalogItemPo();
        po.setTenantId(tenantId);
        po.setStoreId(req.storeId());
        po.setCategory(req.category() == null || req.category().isBlank() ? "其他" : req.category());
        po.setItemType(req.itemType() == null || req.itemType().isBlank() ? "PRODUCT" : req.itemType());
        po.setName(req.name());
        po.setUnit(req.unit() == null || req.unit().isBlank() ? "份" : req.unit());
        po.setUnitPrice(req.unitPrice());
        po.setDescription(req.description());
        applyRequestImages(po, req);
        po.setStatus("ACTIVE");
        po.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        catalogItemMapper.insert(po);
        return po;
    }

    /** 更新目录项（B 端商户经营）。 */
    @PutMapping("/items/{id}")
    public CatalogItemPo update(@PathVariable Long id, @RequestBody CatalogItemRequest req) {
        try {
            CatalogItemPo po = doUpdate(id, req);
            recordItemAudit(ACTION_UPDATE, po);
            return po;
        } catch (RuntimeException failure) {
            // 失败出口留痕（缺权限/目录项不存在/价格非法/落库失败）：审计只 WARN，异常原样抛出。
            recordItemFailure(ACTION_UPDATE, id, failure);
            throw failure;
        }
    }

    private CatalogItemPo doUpdate(Long id, CatalogItemRequest req) {
        PermissionGuard.require("tenant.store.manage");
        CatalogItemPo po = catalogItemMapper.selectById(id);
        if (po == null) {
            throw new BusinessException("CATALOG_ITEM_NOT_FOUND", "目录项不存在");
        }
        if (req.name() != null && !req.name().isBlank()) po.setName(req.name());
        if (req.category() != null && !req.category().isBlank()) po.setCategory(req.category());
        if (req.itemType() != null && !req.itemType().isBlank()) po.setItemType(req.itemType());
        if (req.unit() != null && !req.unit().isBlank()) po.setUnit(req.unit());
        if (req.unitPrice() != null) {
            if (req.unitPrice().signum() <= 0) {
                throw new ApiException(400, "CATALOG_PRICE_INVALID", "单价需大于 0");
            }
            po.setUnitPrice(req.unitPrice());
        }
        if (req.description() != null) po.setDescription(req.description());
        if (req.imageUrls() != null) applyRequestImages(po, req);
        if (req.sortOrder() != null) po.setSortOrder(req.sortOrder());
        po.setUpdatedAt(LocalDateTime.now());
        catalogItemMapper.updateById(po);
        return po;
    }

    /**
     * 目录项自身图片（纯服务/加项没有关联商品与物料，只能在这里直接维护）。
     * 关联了商品的目录项由商品/物料同步覆盖（见 ProductApplicationService.syncCatalog）。
     */
    private static void applyRequestImages(CatalogItemPo po, CatalogItemRequest req) {
        ItemImages.Images images = ItemImages.normalize(req.imageUrls(), req.mainImageUrl(), "CATALOG_INVALID");
        po.setImageUrls(images.urls());
        po.setMainImageUrl(images.mainImageUrl());
    }

    /** 停用目录项（软删 status → INACTIVE）。 */
    @DeleteMapping("/items/{id}")
    public void disable(@PathVariable Long id) {
        try {
            recordItemAudit(ACTION_DELETE, doDisable(id));
        } catch (RuntimeException failure) {
            // 失败出口留痕（缺权限/目录项不存在/落库失败）：审计只 WARN，异常原样抛出。
            recordItemFailure(ACTION_DELETE, id, failure);
            throw failure;
        }
    }

    private CatalogItemPo doDisable(Long id) {
        PermissionGuard.require("tenant.store.manage");
        CatalogItemPo po = catalogItemMapper.selectById(id);
        if (po == null) {
            throw new BusinessException("CATALOG_ITEM_NOT_FOUND", "目录项不存在");
        }
        po.setStatus("INACTIVE");
        po.setUpdatedAt(LocalDateTime.now());
        catalogItemMapper.updateById(po);
        return po;
    }

    /**
     * 目录项写操作成功留痕：动作码与 {@code AuditActions} 登记的一致，幂等键取「动作 + 目录项 ID」。
     *
     * <p>detail 只放数值/枚举字段，运营填写的名称走 {@code resourceName}
     * （由 {@link AuditClient#buildBody} 统一转义），避免手工拼 JSON 被引号/换行破坏成非法 detail 丢审计。
     * 「删除」在本接口是软删（status → INACTIVE），detail 记下删除后的状态，便于与历史订单快照区分。
     */
    private void recordItemAudit(String action, CatalogItemPo po) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(po.getTenantId())
                .storeId(po.getStoreId())
                .action(action)
                .resourceType(AUDIT_RESOURCE_TYPE)
                .resourceId(String.valueOf(po.getId()))
                .resourceName(po.getName())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .idempotencyKey(action + ":" + po.getId())
                .detailJson("{\"storeId\":" + po.getStoreId() + ",\"unitPrice\":" + po.getUnitPrice()
                        + ",\"status\":\"" + po.getStatus() + "\"}")
                .build());
    }

    /**
     * 目录项写操作失败留痕：动作码与成功路径同码、{@code result=FAILURE} + 稳定错误码
     * （缺权限、越权门店、项不存在、价格非法等拒绝同样要能回溯）。
     *
     * <p>审计只走 {@link AuditClient#recordAsync}（失败仅 WARN），业务异常原样抛出：留痕不改变业务结果。
     * 不带幂等键（重复失败各自留痕，也不覆盖成功路径的稳定键）。
     */
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

    public record CatalogItemRequest(Long storeId, String category, String itemType, String name,
                                     String unit, BigDecimal unitPrice, String description, Integer sortOrder,
                                     List<String> imageUrls, String mainImageUrl) {}
}
