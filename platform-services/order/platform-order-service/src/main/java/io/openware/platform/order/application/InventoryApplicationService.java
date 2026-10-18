package io.openware.platform.order.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.currency.Currency;
import io.openware.infrastructure.currency.CurrencyResolver;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.order.api.dto.InventoryMaterialView;
import io.openware.platform.order.api.dto.InventoryTransactionView;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.model.Product;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.domain.catalog.repository.ProductRepository.ProductQuery;
import io.openware.platform.order.domain.inventory.model.InventoryMaterial;
import io.openware.platform.order.domain.inventory.model.InventoryStock;
import io.openware.platform.order.domain.inventory.model.InventoryTransaction;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository.MaterialPage;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository.MaterialQuery;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository.Persisted;
import io.openware.platform.order.domain.inventory.repository.InventoryStockRepository;
import io.openware.platform.order.domain.inventory.repository.InventoryTransactionRepository;
import io.openware.platform.order.domain.inventory.repository.InventoryTransactionRepository.TransactionPage;
import io.openware.platform.order.domain.inventory.repository.InventoryTransactionRepository.TransactionQuery;
import io.openware.platform.order.infra.cache.InventoryAvailabilityCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 库存应用服务：物料档案、库存余额与流水。
 *
 * <p><b>依赖方向</b>：本类只依赖领域仓储端口（{@link InventoryMaterialRepository} /
 * {@link InventoryStockRepository} / {@link InventoryTransactionRepository}）与目录子域的端口
 * （{@code ProductRepository} / {@code CatalogItemRepository}），不再直接持有任何 MyBatis mapper
 * 或持久化对象；响应用 {@code api.dto} 的视图装配，JSON 与类型处理器只存在于适配器。
 *
 * <p><b>防超卖</b>：所有扣减走 {@code InventoryStockRepository#deductAvailable} 的数据库条件更新
 * （{@code SET on_hand_qty = on_hand_qty - n WHERE on_hand_qty - reserved_qty >= n}），
 * 单条语句内由数据库行锁判定，返回 0 行即库存不足并抛 {@code INVENTORY_INSUFFICIENT}，
 * 因此并发扣减也不会出现负库存。入库/调整/回补走 {@code InventoryStockRepository#increaseOnHand}。
 *
 * <p><b>成本</b>（V24）：入库按该批次单价维护移动加权平均成本 {@code ord_inventory_stock.avg_cost}，
 * 出库只按平均成本结转发生额、不重估；库存成本 = 结存数量 × 平均成本（见 {@link #inventoryCosts}）。
 * 链路口径见 {@link #changeStock(Long, BigDecimal, String, String, String, String, String, BigDecimal)}
 * 与 {@link #movingAverageCost}，全程定点计算。
 *
 * <p><b>读缓存</b>：可用量读路径走 {@link InventoryAvailabilityCache}（TTL + 写后失效），
 * 只用于「能不能点 / 是否售罄」的展示；扣减一律以数据库为准，缓存不可用就回源，
 * 不会因为缓存脏读而放行超卖。
 */
@Service
@Slf4j
public class InventoryApplicationService {
    /**
     * 采购价上限：最小货币单位（分），10^13 分 = 1000 亿元／单位。
     * 超过该值几乎必然是把「元」当「分」提交之类的误填，宁可 400 也不要静默落一个荒谬成本价。
     */
    private static final BigDecimal MAX_PURCHASE_PRICE = new BigDecimal("10000000000000");

    /**
     * 成本金额统一保留 6 位小数（与 {@code decimal(20,6)} 列定义一致）。
     * 所有乘除一律走 {@link BigDecimal} 定点运算并显式指定 scale + {@link RoundingMode}，
     * 绝不使用 double/float，保证同一输入在任何平台得到同一结果。
     */
    private static final int COST_SCALE = 6;

    /** 列表分页默认每页条数（{@code pageSize} 非正或超上限时回落到该值）。 */
    public static final long DEFAULT_PAGE_SIZE = 20L;

    /**
     * 列表分页每页条数上限：超过上限不再静默截断到 200，而是回落到 {@link #DEFAULT_PAGE_SIZE}，
     * 让「我请求了 500 条」与「我只拿到 20 条」的差异在客户端可见，不制造半截数据。
     */
    public static final long MAX_PAGE_SIZE = 200L;

    private final InventoryMaterialRepository materialRepository;
    private final InventoryStockRepository stockRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final ProductRepository productRepository;
    private final CatalogItemRepository catalogItemRepository;
    private final InventoryAvailabilityCache availabilityCache;
    private final AuditClient auditClient;

    @Autowired
    public InventoryApplicationService(InventoryMaterialRepository materialRepository,
                                       InventoryStockRepository stockRepository,
                                       InventoryTransactionRepository transactionRepository,
                                       ProductRepository productRepository,
                                       CatalogItemRepository catalogItemRepository,
                                       InventoryAvailabilityCache availabilityCache,
                                       AuditClient auditClient) {
        this.materialRepository = materialRepository;
        this.stockRepository = stockRepository;
        this.transactionRepository = transactionRepository;
        this.productRepository = productRepository;
        this.catalogItemRepository = catalogItemRepository;
        this.availabilityCache = availabilityCache;
        this.auditClient = auditClient;
    }

    /** 兼容既有单测/装配：显式传入缓存但不传审计客户端时，审计用关闭态客户端。 */
    public InventoryApplicationService(InventoryMaterialRepository materialRepository,
                                       InventoryStockRepository stockRepository,
                                       InventoryTransactionRepository transactionRepository,
                                       ProductRepository productRepository,
                                       CatalogItemRepository catalogItemRepository,
                                       InventoryAvailabilityCache availabilityCache) {
        this(materialRepository, stockRepository, transactionRepository, productRepository, catalogItemRepository,
                availabilityCache, AuditClient.disabled());
    }

    /** 兼容既有单测/装配：不传缓存时用「关闭的缓存」（每次回源，行为与旧实现一致），审计用关闭态客户端。 */
    public InventoryApplicationService(InventoryMaterialRepository materialRepository,
                                       InventoryStockRepository stockRepository,
                                       InventoryTransactionRepository transactionRepository,
                                       ProductRepository productRepository,
                                       CatalogItemRepository catalogItemRepository) {
        this(materialRepository, stockRepository, transactionRepository, productRepository, catalogItemRepository,
                new InventoryAvailabilityCache(0L), AuditClient.disabled());
    }

    /**
     * 物料分页列表：门店内按 关键字（名称或编码模糊）+ 分类 + 状态 过滤，按 id 倒序。
     *
     * <p><b>分页口径</b>：分页作用在物料行上，{@code total} = 命中物料条数（不是当前页条数）；
     * {@code pageSize} 非正或超过 {@link #MAX_PAGE_SIZE} 回落到 {@link #DEFAULT_PAGE_SIZE}，{@code page < 1} 回落到第 1 页。
     * 端口只接受「偏移 + 上限」（{@code MaterialQuery}），本方法负责把页号换算成偏移并装配
     * MyBatis-Plus 分页信封（{@code records}/{@code total}/{@code current}/{@code size}，对外契约不变）。
     *
     * <p>当前页每行的 {@code onHandQty}/{@code reservedQty} 用**一次** {@code IN} 查询批量回填
     * （旧实现逐行 {@code selectOne}，一页 20 行就是 20 次查询）；未建库存行的物料按 0，与
     * {@link #availableQuantities} 的「未建行 = 售罄」口径一致。这两个字段不是
     * {@code ord_inventory_material} 的列，只存在于响应视图里（见 {@link InventoryMaterialView}）。
     *
     * <p><b>时间区间</b>：{@code range} 是按**物料创建时间** {@code ord_inventory_material.created_at}
     * 的闭区间（统一 {@code from}/{@code to} 口径，见 {@link TimeRangeParams}）；为空 = 不筛。
     * 条件直接落在 {@code created_at} 列上，不用函数包裹，保持索引可用。
     */
    public Page<InventoryMaterialView> listMaterials(long page, long pageSize, Long storeId, String status,
                                                     String category, String keyword, TimeRange range) {
        TenantContext context = context();
        if (storeId != null && !storeId.equals(context.storeId())) {
            throw new BusinessException("STORE_SCOPE_DENIED", "无权访问该门店库存");
        }
        PageWindow window = windowOf(page, pageSize);
        MaterialPage found = materialRepository.findPage(MaterialQuery.inStore(
                context.tenantId(),
                storeId,
                trimmedOrNull(status),
                trimmedOrNull(category),
                trimmedOrNull(keyword),
                range == null ? null : range.fromInclusive(),
                range == null ? null : range.toInclusive(),
                offsetOf(window),
                window.size()));
        Page<InventoryMaterialView> result = new Page<>(window.current(), window.size(), found.total());
        result.setRecords(decorateWithStock(context, found.materials()));
        return result;
    }

    /**
     * 单物料可用库存（现有 − 预占，负数夹到 0）：读缓存，未命中回源并回填。
     * 仅用于展示/是否可点；扣减不得使用本方法的结果。
     */
    public BigDecimal availableQuantity(Long tenantId, Long storeId, Long materialId) {
        if (tenantId == null || storeId == null || materialId == null) {
            return BigDecimal.ZERO;
        }
        Optional<BigDecimal> cached = availabilityCache.get(tenantId, storeId, materialId);
        if (cached.isPresent()) {
            return cached.get();
        }
        BigDecimal available = loadAvailable(tenantId, storeId, materialId);
        availabilityCache.put(tenantId, storeId, materialId, available);
        return available;
    }

    /** 是否还有可用库存（点单列表的「已售罄」判断）。 */
    public boolean isAvailable(Long tenantId, Long storeId, Long materialId) {
        return availableQuantity(tenantId, storeId, materialId).signum() > 0;
    }

    /**
     * 批量可用库存：点单列表一次取回所有物料的库存，避免逐项查库（N+1），并复用缓存。
     * 未建库存行 / 未返回的物料按 0 处理（= 售罄），与「可用库存不足即不可点」一致。
     */
    public Map<Long, BigDecimal> availableQuantities(Long tenantId, Long storeId, Collection<Long> materialIds) {
        if (tenantId == null || storeId == null || materialIds == null || materialIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        List<Long> misses = new ArrayList<>();
        for (Long materialId : new LinkedHashSet<>(materialIds)) {
            if (materialId == null) {
                continue;
            }
            Optional<BigDecimal> cached = availabilityCache.get(tenantId, storeId, materialId);
            if (cached.isPresent()) {
                result.put(materialId, cached.get());
            } else {
                misses.add(materialId);
            }
        }
        if (!misses.isEmpty()) {
            Map<Long, BigDecimal> loaded = loadAvailableBatch(tenantId, storeId, misses);
            for (Long materialId : misses) {
                BigDecimal available = loaded.getOrDefault(materialId, BigDecimal.ZERO);
                result.put(materialId, available);
                availabilityCache.put(tenantId, storeId, materialId, available);
            }
        }
        return result;
    }

    private BigDecimal loadAvailable(Long tenantId, Long storeId, Long materialId) {
        return availableOf(stockRepository.find(tenantId, storeId, materialId).orElse(null));
    }

    private Map<Long, BigDecimal> loadAvailableBatch(Long tenantId, Long storeId, Collection<Long> materialIds) {
        Map<Long, BigDecimal> result = new HashMap<>();
        for (InventoryStock stock : stockRepository.findByMaterials(tenantId, storeId, List.copyOf(materialIds))) {
            if (stock.materialId() != null) {
                result.put(stock.materialId(), availableOf(stock));
            }
        }
        return result;
    }

    /** 可用量 = on_hand − reserved，负数夹到 0（展示口径，绝不参与扣减判定）。 */
    private static BigDecimal availableOf(InventoryStock stock) {
        if (stock == null) {
            return BigDecimal.ZERO;
        }
        return stock.availableQty().max(BigDecimal.ZERO);
    }

    @Transactional
    public InventoryMaterialView createMaterial(MaterialCommand command) {
        try {
            return doCreateMaterial(command);
        } catch (RuntimeException failure) {
            // 新建物料失败留痕（越权门店/编码名称非法/采购价或图片非法/落库失败）：与成功同码。
            recordStockFailure("inventory.material.create", null,
                    command == null ? null : command.materialCode(), failure);
            throw failure;
        }
    }

    private InventoryMaterialView doCreateMaterial(MaterialCommand command) {
        TenantContext context = context();
        if (context.storeId() == null || command.storeId() == null || !context.storeId().equals(command.storeId())) {
            throw new BusinessException("STORE_SCOPE_DENIED", "无权操作该门店");
        }
        if (command.materialCode() == null || command.materialCode().isBlank() || command.name() == null || command.name().isBlank()) {
            throw new BusinessException("MATERIAL_INVALID", "物料编码和名称不能为空");
        }
        ItemImages.Images images = ItemImages.normalize(command.imageUrls(), command.mainImageUrl(), "MATERIAL_INVALID");
        InventoryMaterial material = InventoryMaterial.draft(
                context.tenantId(), command.storeId(),
                command.materialCode().trim(), command.name().trim(),
                blankDefault(command.category(), "其他"), blankDefault(command.unit(), "份"),
                ItemDescriptions.normalize(command.description(), "MATERIAL_INVALID"),
                command.safetyStock() == null ? BigDecimal.ZERO : positive(command.safetyStock(), "安全库存不能为负"),
                normalizePurchasePrice(command.purchasePrice()),
                // 采购价币种快照（16_CURRENCY_CONVENTIONS §5/§6「库存采购价/入库成本」）：落库时固化当时租户币种。
                CurrencyResolver.currentCode(),
                images.urls(), images.mainImageUrl());
        Persisted<InventoryMaterial> persisted = materialRepository.insert(material);
        InventoryMaterial created = persisted.aggregate();
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action("inventory.material.create")
                .resourceType("inventory_material").resourceId(String.valueOf(created.id()))
                .resourceName(created.name())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .idempotencyKey("inventory-material-create:" + created.id())
                .detailJson("{\"storeId\":" + created.storeId() + ",\"materialCode\":\"" + created.materialCode()
                        + "\",\"unit\":\"" + created.unit() + "\"}")
                .build());
        // 写入路径响应不带结存数量（这两个字段不是列，只在列表读路径批量回填）——与迁移前一致。
        return InventoryMaterialView.from(created, persisted.audit(), null, null);
    }

    /** 编辑物料：编码不可改，名称/分类/单位/描述/安全库存/采购价/图片按需覆盖。 */
    @Transactional
    public InventoryMaterialView updateMaterial(Long id, MaterialCommand command) {
        try {
            return doUpdateMaterial(id, command);
        } catch (RuntimeException failure) {
            // 编辑物料失败留痕（物料不存在/安全库存或采购价非法/图片非法/落库失败）：与成功同码。
            recordStockFailure("inventory.material.update", id, null, failure);
            throw failure;
        }
    }

    private InventoryMaterialView doUpdateMaterial(Long id, MaterialCommand command) {
        TenantContext context = context();
        InventoryMaterial existing = materialRepository.findInStore(context.tenantId(), context.storeId(), id)
                .orElseThrow(() -> new BusinessException("MATERIAL_NOT_FOUND", "物料不存在"));
        // 「部分更新」在这里解析：null / 空白 = 本次不改（沿用既有值），显式值才覆盖。
        // 落库侧由适配器把可编辑列整组显式写出，因此「清空」（描述传空白、采购价传 0、图片传空数组
        // 都归一为 null 或空）是**结构性**能做到的：updateById 的 NOT_NULL 策略会把 null 整列跳过，
        // 会出现「响应已清空、库里还在」的假成功（后台清不掉描述、取消不掉采购价、也删不掉物料图片）。
        String name = command.name() != null && !command.name().isBlank()
                ? command.name().trim() : existing.name();
        String category = command.category() != null && !command.category().isBlank()
                ? command.category().trim() : existing.category();
        String unit = command.unit() != null && !command.unit().isBlank() ? command.unit().trim() : existing.unit();
        String description = command.description() != null
                ? ItemDescriptions.normalize(command.description(), "MATERIAL_INVALID") : existing.description();
        BigDecimal safetyStock = command.safetyStock() != null
                ? positive(command.safetyStock(), "安全库存不能为负") : existing.safetyStock();
        // null = 不修改；0 = 清空（归一为 null 落库）；> 0 才覆盖，非法值 400。见 normalizePurchasePrice。
        BigDecimal purchasePrice = existing.purchasePrice();
        // 采购价被改写时同步刷新其币种快照：价格与币种必须同源，否则成本报表会按旧币种归集。
        String currencyCode = existing.currencyCode();
        if (command.purchasePrice() != null) {
            purchasePrice = normalizePurchasePrice(command.purchasePrice());
            currencyCode = CurrencyResolver.currentCode();
        }
        List<String> imageUrls = existing.imageUrls();
        String mainImageUrl = existing.mainImageUrl();
        if (command.imageUrls() != null) {
            ItemImages.Images images = ItemImages.normalize(command.imageUrls(), command.mainImageUrl(), "MATERIAL_INVALID");
            imageUrls = images.urls();
            mainImageUrl = images.mainImageUrl();
        }
        InventoryMaterial desired = existing.edited(name, category, unit, description, safetyStock, purchasePrice,
                currencyCode, imageUrls, mainImageUrl);
        Persisted<InventoryMaterial> persisted;
        try {
            int expectedVersion = desired.version() == null ? 0 : desired.version();
            persisted = materialRepository.update(desired, expectedVersion);
        } catch (IllegalStateException conflict) {
            throw new BusinessException("INVENTORY_MATERIAL_VERSION_CONFLICT", "物料已被并发修改，请刷新后重试");
        }
        InventoryMaterial updated = persisted.aggregate();
        syncCatalogImages(updated);
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action("inventory.material.update")
                .resourceType("inventory_material").resourceId(String.valueOf(updated.id()))
                .resourceName(updated.name())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson("{\"storeId\":" + updated.storeId() + ",\"safetyStock\":" + updated.safetyStock()
                        + ",\"purchasePrice\":" + updated.purchasePrice() + "}")
                .build());
        return InventoryMaterialView.from(updated, persisted.audit(), null, null);
    }

    /**
     * 物料图片变更后，同步到「镜像该物料图片」的点单目录项。
     * 商品自己有图时目录项以商品图为准（见 ProductApplicationService.syncCatalog），这里不覆盖，避免把商品图冲掉。
     */
    private void syncCatalogImages(InventoryMaterial material) {
        List<Product> products = productRepository.find(
                ProductQuery.linkedToMaterial(material.tenantId(), material.storeId(), material.id()));
        List<String> urls = material.imagesOrEmpty();
        for (Product product : products) {
            if (product.catalogItemId() == null) {
                continue;
            }
            if (!product.imagesOrEmpty().isEmpty()) {
                continue;
            }
            Optional<CatalogItem> catalog = catalogItemRepository.findById(product.catalogItemId());
            if (catalog.isEmpty()) {
                continue;
            }
            // 镜像图片（返回新实例）；审计列由目录适配器在写入时刷新。
            catalogItemRepository.update(catalog.get().withMirroredImages(urls, material.mainImageUrl()));
        }
    }

    @Transactional
    public InventoryTransactionView changeStock(Long materialId, BigDecimal quantity, String type, String reason, String key) {
        return changeStock(materialId, quantity, type, "ADJUSTMENT", null, reason, key, null);
    }

    /**
     * 库存变更唯一入口：入库（RECEIPT/ADJUST_IN/REVERSE）原子加，出库（CONSUME/ADJUST_OUT）
     * 原子条件扣减（不足即 {@code INVENTORY_INSUFFICIENT}，绝不为负），成功后写流水并失效可用量缓存。
     * 幂等键 (tenant_id, idempotency_key) 保证重放返回首次流水、不重复扣减。
     *
     * <p><b>一致性</b>：库存更新与库存流水在同一事务内提交；调用方（如加项）失败回滚时，
     * 扣减与流水一起回滚，不会出现「扣了库存没写流水」或「写了明细没扣库存」。
     * 缓存失效发生在更新之后：即使事务随后回滚，也只是多做一次失效，下一次读自然回源拿到真实值。
     *
     * <p><b>失败路径</b>：条件更新返回 0 行（可用量不足）或物料不存在/已停用即抛业务异常，
     * 由本模块 GlobalExceptionHandler 统一输出 {code,message}，不泄露 500。
     *
     * <p>本重载不指定入库批次单价：入库时按 {@link #resolveReceiptCost} 的口径取
     * （回补优先还原原出库单价 → 物料 {@code purchase_price} → 0）。
     */
    @Transactional
    public InventoryTransactionView changeStock(Long materialId, BigDecimal quantity, String type, String sourceType,
                                               String sourceId, String reason, String key) {
        return changeStock(materialId, quantity, type, sourceType, sourceId, reason, key, null);
    }

    /**
     * 库存变更唯一入口（带**入库批次单价**）。
     *
     * <p><b>成本口径（V24 移动加权平均）</b>：
     * <ul>
     *   <li>入库：以本次批次单价 {@code receiptUnitCost} 参与加权，
     *       {@code avg = (onHand * avgCost + qty * receiptUnitCost) / (onHand + qty)}；
     *       同时把该批次单价与币种写进流水的 {@code unit_cost/currency_code} 以便追溯。</li>
     *   <li>出库：**不改变**平均成本，只按当前平均成本结转发生额
     *       {@code total_cost = -qty * avgCost}，并把当次单价（= 平均成本）写进流水。</li>
     *   <li>并发：加权用的「入库前数量 + 旧平均成本」在 {@code findForUpdate} 行锁内读出，
     *       锁持续到事务提交，因此同一 (租户, 门店, 物料) 的并发入库严格串行，不会丢失更新。</li>
     * </ul>
     *
     * @param receiptUnitCost 入库批次单价（最小货币单位/计量单位，per {@code unit}）；null 或 0 = 未填，
     *                        沿用 {@link #resolveReceiptCost} 的取值链；为负或超上限 → 400 {@code PURCHASE_PRICE_INVALID}。
     *                        出库时本参数被忽略（单价恒等于当前移动加权平均成本）。
     */
    @Transactional
    public InventoryTransactionView changeStock(Long materialId, BigDecimal quantity, String type, String sourceType,
                                               String sourceId, String reason, String key, BigDecimal receiptUnitCost) {
        try {
            return doChangeStock(materialId, quantity, type, sourceType, sourceId, reason, key, receiptUnitCost);
        } catch (RuntimeException failure) {
            // 库存变更失败留痕（物料停用/库存不足/跨币种加权/系数非法/落库失败）：
            // 只覆盖「人主动发起」的入库/调整（与 recordStockAudit 同一动作映射），
            // 加项出库(CONSUME)与作废回补(REVERSE)的失败由各自业务入口留痕，避免一次业务两条同义审计。
            String action = actionOf(type);
            if (action != null) {
                recordStockFailure(action, materialId, null, failure);
            }
            throw failure;
        }
    }

    private InventoryTransactionView doChangeStock(Long materialId, BigDecimal quantity, String type, String sourceType,
                                                   String sourceId, String reason, String key, BigDecimal receiptUnitCost) {
        TenantContext context = context();
        if (quantity == null || quantity.signum() <= 0 || key == null || key.isBlank()) {
            throw new BusinessException("INVENTORY_INVALID", "数量必须大于0且必须提供幂等键");
        }
        Optional<InventoryTransaction> existing = transactionRepository.findByIdempotency(context.tenantId(), key);
        if (existing.isPresent()) {
            return InventoryTransactionView.from(existing.get());
        }
        InventoryMaterial material = materialRepository
                .findInStore(context.tenantId(), context.storeId(), materialId)
                .orElseThrow(() -> new BusinessException("MATERIAL_NOT_FOUND", "物料不存在或已停用"));
        if (!material.active()) {
            throw new BusinessException("MATERIAL_NOT_FOUND", "物料不存在或已停用");
        }
        // 行锁只用来「取准确的变更前后值写流水」与串行化同一物料的并发变更；
        // 真正的防超卖判定在下面的条件更新里由数据库保证（不依赖本处读到的值）。
        // 成本侧同样依赖这把锁：加权平均必须在锁内读旧值、算新值、再写回，否则并发批次会互相覆盖。
        InventoryStock stock = lockStockRow(context, materialId);
        BigDecimal before = stock.onHandOrZero();
        BigDecimal avgCost = stock.avgCostOrZero();
        String stockCurrency = normalizedCurrency(stock.currencyCode());
        boolean inbound = InventoryTransaction.inbound(type);
        BigDecimal unitCost;
        String currencyCode;
        if (inbound) {
            ReceiptCost receipt = resolveReceiptCost(context, material, type, sourceType, sourceId, receiptUnitCost);
            unitCost = receipt.unitCost();
            currencyCode = receipt.currencyCode();
            avgCost = movingAverageCost(before, avgCost, stockCurrency, quantity, unitCost, currencyCode);
        } else {
            // 出库只结转、不重估：单价就是当前平均成本，币种就是该平均成本的币种快照。
            unitCost = avgCost;
            currencyCode = stockCurrency;
        }
        LocalDateTime now = LocalDateTime.now();
        // 入库 = 单条语句内同时加现有量与落库新成本；出库 = 单条带上下界的条件扣减。
        // 两者都由数据库以行锁判定，返回值就是权威结论（0 行 = 库存不足 / 目标行不存在）。
        int affected = inbound
                ? stockRepository.increaseOnHand(context.tenantId(), context.storeId(), materialId, quantity,
                        avgCost, currencyCode, now)
                : (stockRepository.deductAvailable(context.tenantId(), context.storeId(), materialId, quantity, now)
                        ? 1 : 0);
        if (affected == 0) {
            throw new BusinessException("INVENTORY_INSUFFICIENT", "可用库存不足");
        }
        BigDecimal signedQuantity = inbound ? quantity : quantity.negate();
        InventoryTransaction tx = new InventoryTransaction(null, context.tenantId(), context.storeId(), materialId,
                type, signedQuantity, before, before.add(signedQuantity),
                // 批次单价与成本发生额（带符号：入库正、出库负）——入库可追溯到具体批次，出库可追溯到当次结转依据。
                scale(unitCost), scale(signedQuantity.multiply(unitCost)), currencyCode,
                sourceType, sourceId, key, reason, now);
        InventoryTransaction stored = transactionRepository.append(tx);
        // 写后失效：本实例立即回源；其他实例最坏在 TTL 内看到旧可用量（展示层，不影响扣减）。
        availabilityCache.invalidate(context.tenantId(), context.storeId(), materialId);
        recordStockAudit(material, stored, type, key);
        return InventoryTransactionView.from(stored);
    }

    /**
     * 入库批次单价与币种的取值链（显式传入优先）：
     * <ol>
     *   <li>显式传入 &gt; 0：直接采用，币种取当前请求币种（调用方按当前币种录入的采购单价）；</li>
     *   <li>作废回补 REVERSE：按原出库流水 {@code unit_cost} 还原（成本不变地回补），找不到才继续往下；</li>
     *   <li>物料 {@code purchase_price} &gt; 0：沿用物料采购价与其币种快照（后台未单独录批次价时的缺省）；</li>
     *   <li>都没有：0 成本 + 当前请求币种（= 尚未建立成本基准，后续出库按 0 结转，报表里以「无成本数量」显式标注）。</li>
     * </ol>
     * 显式传入 0 与 null 同义（本模块既有「0 = 未填」三态约定，见 {@code normalizePurchasePrice}），
     * 避免前端把「留空」提交成 0 时把整批库存的平均成本稀释到 0。
     */
    private ReceiptCost resolveReceiptCost(TenantContext context, InventoryMaterial material, String type,
                                           String sourceType, String sourceId, BigDecimal requestedUnitCost) {
        if (requestedUnitCost != null) {
            if (requestedUnitCost.signum() < 0) {
                throw new BusinessException("PURCHASE_PRICE_INVALID", "入库单价（最小货币单位）不能为负");
            }
            if (requestedUnitCost.compareTo(MAX_PURCHASE_PRICE) > 0) {
                throw new BusinessException("PURCHASE_PRICE_INVALID", "入库单价（最小货币单位）超出上限");
            }
            if (requestedUnitCost.signum() > 0) {
                return new ReceiptCost(requestedUnitCost, CurrencyResolver.currentCode());
            }
        }
        if (InventoryTransaction.TYPE_REVERSE.equals(type) && sourceType != null && sourceId != null) {
            Optional<InventoryTransaction> outbound = transactionRepository.findLatestOutbound(
                    context.tenantId(), context.storeId(), material.id(), sourceType, sourceId);
            if (outbound.isPresent() && outbound.get().unitCost() != null
                    && outbound.get().unitCost().signum() > 0) {
                return new ReceiptCost(outbound.get().unitCost(), normalizedCurrency(outbound.get().currencyCode()));
            }
        }
        if (material.purchasePrice() != null && material.purchasePrice().signum() > 0) {
            return new ReceiptCost(material.purchasePrice(), normalizedCurrency(material.currencyCode()));
        }
        return new ReceiptCost(BigDecimal.ZERO, CurrencyResolver.currentCode());
    }

    /**
     * 移动加权平均成本重算（定点，全程 {@link BigDecimal}，禁止浮点）：
     * <pre>avg = (onHand * avgCost + qty * receiptUnitCost) / (onHand + qty)</pre>
     *
     * <p><b>除零/边界</b>：{@code onHand <= 0} 时没有存量可加权，分母无意义，直接取该批次单价
     * （等价于以本批次重新建账，含历史脏数据下的负库存）。
     *
     * <p><b>跨币种</b>：旧成本基准非 0 且币种与本批次不一致时，{@code onHand * avgCost} 与
     * {@code qty * receiptUnitCost} 是两种货币的金额，相加没有汇率依据 —— 直接拒绝
     * {@code INVENTORY_CURRENCY_MISMATCH}，绝不静默混合。旧基准为 0 时不构成跨币种相加
     * （0 乘以任何数量的价值都是 0），允许按本批次币种重新建账。
     *
     * <p><b>舍入</b>：结果保留 {@link #COST_SCALE} 位、{@link RoundingMode#HALF_UP}，
     * 与 {@code avg_cost decimal(20,6)} 列精度一致，任何平台可重复。
     */
    private static BigDecimal movingAverageCost(BigDecimal onHand, BigDecimal avgCost, String stockCurrency,
                                                BigDecimal quantity, BigDecimal receiptUnitCost, String receiptCurrency) {
        if (onHand.signum() <= 0) {
            return scale(receiptUnitCost);
        }
        if (avgCost.signum() > 0 && !normalizedCurrency(stockCurrency).equals(normalizedCurrency(receiptCurrency))) {
            throw new BusinessException("INVENTORY_CURRENCY_MISMATCH",
                    "该物料库存的平均成本币种为 " + normalizedCurrency(stockCurrency)
                            + "，与本次入库批次币种 " + normalizedCurrency(receiptCurrency)
                            + " 不一致；跨币种不能直接加权，请先完成成本调整或待库存清零后再入库");
        }
        BigDecimal existingValue = onHand.multiply(avgCost);
        BigDecimal inboundValue = quantity.multiply(receiptUnitCost);
        return existingValue.add(inboundValue)
                .divide(onHand.add(quantity), COST_SCALE, RoundingMode.HALF_UP);
    }

    /** 币种快照归一：空/未知一律按全仓缺省 USD（{@link Currency#parse}）。 */
    private static String normalizedCurrency(String raw) {
        return Currency.parse(raw).code();
    }

    /** 成本金额定点收敛到列精度（{@link #COST_SCALE} 位，HALF_UP）。 */
    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(COST_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 库存成本查询口径（V24）：**库存成本 = 结存数量 × 移动加权平均成本**，按门店/物料/币种。
     *
     * <p>数量与平均成本都取库存余额行的当前值（结存口径，非期间发生额）；金额在 Java 侧定点相乘，
     * 不在 SQL 里做乘除，避免数据库浮点/隐式转换带来的尾差。
     * 币种取库存行的平均成本快照：单币种时信封给 {@code currencyCode}，混币种时 {@code currencyCode=null}
     * 且 {@code mixedCurrency=true}，调用方必须逐行渲染、不得相加。
     *
     * <p><b>分页口径（重要）</b>：分页只作用于「物料行」（{@code records} 是当前页的物料成本行），
     * 而 {@code total} 与币种信封（{@code currencyCode}/{@code mixedCurrency}）都按**全部命中行**计算，
     * 不是仅当前页 —— 否则翻页时「本页是否混币种」会漂移，合计口径会随页码变化。
     * 代价是每次都要把命中物料全部算一遍（与改造前的实现同量级，单门店物料规模下有界），
     * 换来的是翻页时信封恒定。
     */
    public InventoryCostReport inventoryCosts(long page, long pageSize, Long storeId, String keyword) {
        TenantContext context = context();
        if (storeId != null && !storeId.equals(context.storeId())) {
            throw new BusinessException("STORE_SCOPE_DENIED", "无权访问该门店库存");
        }
        // 越权门店已在上面拒绝，因此这里的门店恒等于上下文门店。
        Long scopedStore = context.storeId();
        String nameOrCode = trimmedOrNull(keyword);
        Set<Long> keywordMaterialIds = nameOrCode == null
                ? null : new HashSet<>(matchingMaterialIds(context, nameOrCode));
        List<InventoryStock> stocks = stockRepository.findByStore(context.tenantId(), scopedStore);
        if (keywordMaterialIds != null) {
            stocks = stocks.stream().filter(stock -> keywordMaterialIds.contains(stock.materialId())).toList();
        }
        List<Long> materialIds = stocks.stream().map(InventoryStock::materialId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, InventoryMaterial> materials = new HashMap<>();
        if (!materialIds.isEmpty()) {
            for (InventoryMaterial material : materialRepository.findByIds(materialIds)) {
                materials.put(material.id(), material);
            }
        }
        Set<String> currencies = new LinkedHashSet<>();
        List<InventoryCostRow> rows = new ArrayList<>();
        for (InventoryStock stock : stocks) {
            InventoryMaterial material = materials.get(stock.materialId());
            BigDecimal onHand = stock.onHandOrZero();
            BigDecimal avgCost = stock.avgCostOrZero();
            String currency = normalizedCurrency(stock.currencyCode());
            currencies.add(currency);
            rows.add(new InventoryCostRow(scopedStore, stock.materialId(),
                    material == null ? null : material.materialCode(),
                    material == null ? null : material.name(),
                    material == null ? null : material.unit(),
                    scale(onHand), scale(avgCost), currency, scale(onHand.multiply(avgCost))));
        }
        PageWindow window = windowOf(page, pageSize);
        InventoryCostReport report = new InventoryCostReport(window.current(), window.size(), rows.size());
        report.setRecords(slicePage(rows, window.current(), window.size()));
        report.setDataAsOf(Instant.now());
        report.setStoreId(scopedStore);
        report.setCurrencyCode(currencies.size() == 1 ? currencies.iterator().next() : null);
        report.setMixedCurrency(currencies.size() > 1);
        return report;
    }

    /**
     * 库存变更留痕：只记「人主动发起」的入库/调整，加项出库(CONSUME)与回补(REVERSE)分别由
     * 加项与后台回补流程留痕，避免一次业务产生两条同义审计。
     */
    private void recordStockAudit(InventoryMaterial material, InventoryTransaction tx, String type, String key) {
        String action = actionOf(type);
        if (action == null) {
            return;
        }
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action(action)
                .resourceType("inventory_material").resourceId(String.valueOf(material.id()))
                .resourceName(material.name())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .idempotencyKey(key)
                .detailJson("{\"storeId\":" + tx.storeId() + ",\"transactionType\":\"" + type
                        + "\",\"quantity\":" + tx.quantityDelta() + ",\"quantityBefore\":" + tx.quantityBefore()
                        + ",\"quantityAfter\":" + tx.quantityAfter() + ",\"reason\":\""
                        + (tx.reason() == null ? "" : tx.reason().replace("\"", "\\\"")) + "\"}")
                .build());
    }

    /** 库存变更类型 → 审计动作码；不产生操作留痕的类型（加项出库/回补）返回 null。 */
    private static String actionOf(String type) {
        if (InventoryTransaction.TYPE_RECEIPT.equals(type)) {
            return "inventory.receipt.create";
        }
        if (InventoryTransaction.TYPE_ADJUST_IN.equals(type) || InventoryTransaction.TYPE_ADJUST_OUT.equals(type)) {
            return "inventory.adjust";
        }
        return null;
    }

    /**
     * 物料/库存写操作失败留痕：动作码与成功路径同码，{@code result=FAILURE} + 稳定 errorCode。
     *
     * <p>审计只走 {@link AuditClient#recordAsync}（失败仅 WARN），业务异常原样抛出：留痕不改变业务结果。
     * 不带幂等键（重复失败各自留痕，也不覆盖成功路径的稳定键）；detail 只放定位 ID 与物料编码，
     * <b>不含</b>采购价/成本等金额。
     */
    private void recordStockFailure(String action, Long materialId, String materialCode, RuntimeException failure) {
        TenantContext context = TenantContextHolder.get();
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(context == null ? null : context.tenantId())
                .storeId(context == null ? null : context.storeId())
                .action(action)
                .resourceType("inventory_material")
                .resourceId(materialId == null ? null : String.valueOf(materialId))
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"materialId\":" + materialId + ",\"materialCode\":" + jsonText(materialCode) + "}")
                .build());
    }

    /** 最小 JSON 字符串转义（物料编码来自运营输入，未转义会拼出非法 JSON 丢审计）。 */
    private static String jsonText(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * 取库存行并加行锁；不存在则先建 0 行。并发建行由唯一键 (tenant_id, store_id, material_id) 兜底，
     * 抢输的事务忽略冲突后重新加锁读取，不会出现两行库存。
     *
     * <p>建行走 {@code save}（端口上只有插入语义，没有整行写回，见端口说明）；
     * 重新加锁后若仍读不到行，说明该行既没被本事务建成、也不存在于库中——返回空会让后续
     * {@code onHandQty()} 取到 null，因此这里给出稳定的业务错误码而不是放任 NPE。
     */
    private InventoryStock lockStockRow(TenantContext context, Long materialId) {
        Optional<InventoryStock> stock = stockRepository.findForUpdate(context.tenantId(), context.storeId(), materialId);
        if (stock.isPresent()) {
            return stock.get();
        }
        try {
            stockRepository.save(InventoryStock.empty(context.tenantId(), context.storeId(), materialId));
        } catch (DuplicateKeyException e) {
            log.debug("库存行已被并发事务创建，忽略并重新加锁读取: tenantId={} storeId={} materialId={}",
                    context.tenantId(), context.storeId(), materialId);
        }
        return stockRepository.findForUpdate(context.tenantId(), context.storeId(), materialId)
                .orElseThrow(() -> new BusinessException("INVENTORY_INVALID", "库存行不存在"));
    }

    /**
     * 库存流水分页列表：当前门店内按 物料 + 类型 + 来源 + 发生时间闭区间 + 关键字（物料名/编码）过滤，
     * 按 id 倒序（等价于发生时间倒序，同毫秒写入时也稳定）。
     *
     * <p><b>时间口径</b>：本表没有独立的 {@code occurred_at} 列，流水的发生时刻就是 {@code created_at}
     * （{@code datetime(3)}，见 V11/V24 迁移），因此区间过滤落在 {@code created_at} 上、与表列一致。
     *
     * <p>{@code range} 由对外的 {@code from}/{@code to} 经**全仓统一**的 {@link TimeRangeParams#parse}
     * 解析而来（{@code yyyy-MM-dd} 的 {@code from} → 当天 00:00:00.000、{@code to} → 当天 23:59:59.999；
     * 时刻形态按字面值），闭区间；{@code from > to} → 400 {@code TIME_RANGE_INVALID}。
     * 本方法**不再自己解析时间字符串**——此前的 {@code INVENTORY_FILTER_INVALID} 与私有
     * {@code parseFilterBound} 已删除，避免同一仓出现第二套时间口径。
     *
     * <p><b>关键字</b>：流水表不存物料名快照，先按名称/编码取出命中物料 id 集合，再在分页条件里
     * {@code IN} 该集合（两条简单查询），既不 join 分页表，也不做逐行回查的 N+1；命中为空直接返回空页。
     */
    public Page<InventoryTransactionView> listTransactions(long page, long pageSize, Long materialId,
                                                           String transactionType, String sourceType,
                                                           TimeRange range, String keyword) {
        TenantContext context = context();
        PageWindow window = windowOf(page, pageSize);
        String nameOrCode = trimmedOrNull(keyword);
        List<Long> matchedMaterialIds = null;
        if (nameOrCode != null) {
            matchedMaterialIds = matchingMaterialIds(context, nameOrCode);
            if (matchedMaterialIds.isEmpty()) {
                // 关键字没有命中任何物料：空页（total=0），不能下发 IN () 这种非法 SQL。
                Page<InventoryTransactionView> empty = new Page<>(window.current(), window.size(), 0L);
                empty.setRecords(List.of());
                return empty;
            }
        }
        TransactionPage found = transactionRepository.findPage(TransactionQuery.inStore(
                context.tenantId(),
                context.storeId(),
                materialId,
                trimmedOrNull(transactionType),
                trimmedOrNull(sourceType),
                range == null ? null : range.fromInclusive(),
                range == null ? null : range.toInclusive(),
                matchedMaterialIds,
                offsetOf(window),
                window.size()));
        Page<InventoryTransactionView> result = new Page<>(window.current(), window.size(), found.total());
        result.setRecords(found.transactions().stream().map(InventoryTransactionView::from).toList());
        return result;
    }

    private TenantContext context() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0 || context.storeId() == null) throw new BusinessException("TENANT_CONTEXT_REQUIRED", "缺少有效门店上下文");
        return context;
    }

    /** 归一化分页参数：{@code page < 1} 回落到第 1 页；{@code pageSize} 非正或超上限回落到默认 20。 */
    private static PageWindow windowOf(long page, long pageSize) {
        long current = page < 1 ? 1L : page;
        long size = pageSize < 1 || pageSize > MAX_PAGE_SIZE ? DEFAULT_PAGE_SIZE : pageSize;
        return new PageWindow(current, size);
    }

    /**
     * 页号 → 端口要的偏移量。
     *
     * <p>先把页数夹到 {@code Long.MAX_VALUE / size} 再相乘，避免畸形 {@code page}（如
     * {@code Long.MAX_VALUE}）造成 long 溢出后下发负偏移（那是非法 SQL）。
     */
    private static long offsetOf(PageWindow window) {
        long maxPagesBefore = Long.MAX_VALUE / window.size();
        return Math.min(window.current() - 1, maxPagesBefore) * window.size();
    }

    /**
     * 内存分页切片（库存成本的物料行已在 Java 侧聚合，见 {@link #inventoryCosts}）。
     * 越界页返回空列表而不是报错，与 MyBatis-Plus「超出总页数返回空 records」一致；
     * 偏移量先与总行数比较再相乘，避免畸形 {@code page} 造成的 long 溢出。
     */
    private static List<InventoryCostRow> slicePage(List<InventoryCostRow> rows, long current, long size) {
        if (rows.isEmpty()) {
            return List.of();
        }
        long offset = current <= 1 ? 0L
                : (current - 1 > rows.size() ? rows.size() : (current - 1) * size);
        if (offset >= rows.size()) {
            return List.of();
        }
        return List.copyOf(rows.subList((int) offset, (int) Math.min(offset + size, rows.size())));
    }

    /**
     * 关键字命中的物料 id（当前租户 + 当前门店，名称或编码模糊）。
     * 流水/成本要按物料名检索，但两张表都没有名称快照：先取 id 集合再 {@code IN}，
     * 两条简单查询即可，不引入联表分页与逐行回查（N+1）。
     */
    private List<Long> matchingMaterialIds(TenantContext context, String nameOrCode) {
        return materialRepository.findIdsByNameOrCode(context.tenantId(), context.storeId(), nameOrCode);
    }

    /**
     * 把当前页物料装配成响应视图，并用**一次** {@code IN} 查询批量回填结存/预占数量。
     *
     * <p>{@code onHandQty}/{@code reservedQty} 不是 {@code ord_inventory_material} 的列
     * （见 {@link InventoryMaterialView}）：未建库存行的物料按 0，与 {@link #availableQuantities}
     * 的「未建行 = 售罄」口径一致。
     */
    private List<InventoryMaterialView> decorateWithStock(TenantContext context, List<InventoryMaterial> materials) {
        if (materials.isEmpty()) {
            return List.of();
        }
        List<Long> materialIds = materials.stream().map(InventoryMaterial::id).filter(Objects::nonNull).toList();
        Map<Long, InventoryStock> stockByMaterial = new HashMap<>();
        if (!materialIds.isEmpty()) {
            for (InventoryStock stock : stockRepository.findByMaterials(context.tenantId(), context.storeId(), materialIds)) {
                if (stock.materialId() != null) {
                    stockByMaterial.put(stock.materialId(), stock);
                }
            }
        }
        List<InventoryMaterialView> views = new ArrayList<>(materials.size());
        for (InventoryMaterial material : materials) {
            InventoryStock stock = stockByMaterial.get(material.id());
            views.add(InventoryMaterialView.from(material, null,
                    stock == null ? BigDecimal.ZERO : stock.onHandOrZero(),
                    stock == null ? BigDecimal.ZERO : stock.reservedQty()));
        }
        return views;
    }

    /** 查询筛选值归一：空白一律当作「未传」，避免用空串去匹配出永远为空的列表。 */
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    /** 空白筛选值归一为 null（调用方据此跳过该条件）。 */
    private static String trimmedOrNull(String value) { return hasText(value) ? value.trim() : null; }

    private static String blankDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static BigDecimal positive(BigDecimal value, String message) { if (value.signum() < 0) throw new BusinessException("INVENTORY_INVALID", message); return value; }

    /**
     * 采购价归一（最小货币单位：分，每计量单位），沿用本模块既有的「null = 不修改，0 = 清空」部分更新约定
     * （同类语义见 {@code ProductApplicationService#update} 的 materialId、资源域 roomTypeId）：
     * <ul>
     *   <li>null：调用方未提供该字段 → 返回 null，更新路径据此跳过（不修改）；新建路径即「未填」，落库 NULL。</li>
     *   <li>0：清空采购价 → 返回 null 作为列值，即写 NULL 而不是 0 分，避免「0 分」与「未维护」两种含义混用。</li>
     *   <li>&gt; 0：实际采购价，上限 {@link #MAX_PURCHASE_PRICE}；为负或超上限一律 400 {@code PURCHASE_PRICE_INVALID}。</li>
     * </ul>
     * 注意 0 分本身没有业务含义（采购价 0 元 = 未维护），因此前端把「留空」也提交为 0，两条路径落库一致。
     */
    private static BigDecimal normalizePurchasePrice(BigDecimal value) {
        if (value == null || value.signum() == 0) return null;
        if (value.signum() < 0) throw new BusinessException("PURCHASE_PRICE_INVALID", "采购价（最小货币单位）不能为负");
        if (value.compareTo(MAX_PURCHASE_PRICE) > 0) {
            throw new BusinessException("PURCHASE_PRICE_INVALID", "采购价（最小货币单位）超出上限");
        }
        return value;
    }

    public record MaterialCommand(Long storeId, String materialCode, String name, String category, String unit,
                                  BigDecimal safetyStock, BigDecimal purchasePrice, String description,
                                  List<String> imageUrls, String mainImageUrl) {}

    /**
     * 库存成本查询结果：MyBatis-Plus {@link Page} 信封（{@code records}/{@code total}/{@code current}/{@code size}）
     * + 币种信封（{@code currencyCode}/{@code mixedCurrency}）。
     *
     * <p>{@code currencyCode} 为单币种时的该币种；混币种时 {@code currencyCode=null} 且
     * {@code mixedCurrency=true}，调用方必须逐行按行内 {@code currencyCode} 渲染，不得把多行金额相加。
     *
     * <p>继承 {@link Page} 而不是自定义信封：与仓库其它分页端点（如 {@code Page<CstMemberPo>}）保持同一响应形态，
     * 前端一套分页组件即可消费；{@code total}/{@code currencyCode}/{@code mixedCurrency} 均按**全部命中行**计算，
     * {@code records} 只是当前页的物料行。
     */
    public static class InventoryCostReport extends Page<InventoryCostRow> {
        /** 数据时点（本次查询时刻，结存口径的 as-of）。 */
        private Instant dataAsOf;
        /** 报表门店（受门店上下文限制，恒等于当前上下文门店）。 */
        private Long storeId;
        /** 全部命中行的币种：单币种给该币种，混币种为 null（配合 mixedCurrency=true）。 */
        private String currencyCode;
        /** 全部命中行是否混币种（true 时禁止把各行金额相加）。 */
        private boolean mixedCurrency;

        public InventoryCostReport(long current, long size, long total) { super(current, size, total); }

        public Instant getDataAsOf() { return dataAsOf; }
        public void setDataAsOf(Instant dataAsOf) { this.dataAsOf = dataAsOf; }
        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }
        public String getCurrencyCode() { return currencyCode; }
        public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
        public boolean isMixedCurrency() { return mixedCurrency; }
        public void setMixedCurrency(boolean mixedCurrency) { this.mixedCurrency = mixedCurrency; }
    }

    /** 单行库存成本（门店/物料/币种粒度）。{@code inventoryCost = onHandQty × avgCost}，均为最小货币单位。 */
    public record InventoryCostRow(Long storeId, Long materialId, String materialCode, String materialName, String unit,
                                   BigDecimal onHandQty, BigDecimal avgCost, String currencyCode,
                                   BigDecimal inventoryCost) {}

    /** 一次入库的批次单价与币种（内部取值链结果，不对外暴露）。 */
    private record ReceiptCost(BigDecimal unitCost, String currencyCode) {}

    /** 归一化后的分页窗口（页号 + 每页条数）；只在本类内部使用，端口侧只用「偏移 + 上限」。 */
    private record PageWindow(long current, long size) {}
}
