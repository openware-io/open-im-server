package com.gvchat.platform.order.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.audit.AuditErrorCodes;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.infrastructure.time.TimeRangeParams;
import com.gvchat.infrastructure.time.TimeRangeParams.TimeRange;
import com.gvchat.platform.order.infra.client.ResourceStateClient;
import com.gvchat.platform.order.infra.client.ResourceStateClient.ServerSnapshot;
import com.gvchat.platform.order.infra.persistence.mapper.CatalogItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.InventoryMaterialMapper;
import com.gvchat.platform.order.infra.persistence.mapper.ProductMapper;
import com.gvchat.platform.order.infra.persistence.po.CatalogItemPo;
import com.gvchat.platform.order.infra.persistence.po.InventoryMaterialPo;
import com.gvchat.platform.order.infra.persistence.po.ProductPo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品（实物 / 服务）的创建、编辑、上下架与点单目录同步。
 *
 * <p><b>商品类型权威口径</b>：{@code ord_product.item_type}（PRODUCT 实物商品 / SERVICE 服务）。
 * 服务是「人员的服务」，因此服务型商品必须关联一名启用的本门店服务人员
 * （{@code ord_product.server_resource_id} → {@code res_resource.id, resource_type=KTV_SERVER}）。
 *
 * <p><b>「服务人员 ↔ 点单目录项」关联的唯一权威方向</b>：以 {@code ord_product} 为准
 * （{@code server_resource_id} + 既有的 {@code catalog_item_id}），保存商品时把
 * {@code ord_catalog_item.item_type} 与商品类型对齐；「哪个目录项属于哪位服务人员」由
 * {@code ord_catalog_item.product_id → ord_product.server_resource_id} 反查得到，**不再造第二套关联**。
 * 资源侧（{@code res_resource}）目前没有 {@code attributes_json} 列，也无人读取它
 * （platform-admin-service 的 {@code ServerCatalogItem.catalogItemId} 恒为 null），因此不做资源侧写入；
 * 唯一性由 {@code uk_ord_product_server_resource}（tenant_id+store_id+server_resource_id）兜底，
 * 应用层先查一次给出可读的中文冲突提示。
 */
@Service
public class ProductApplicationService {

    /** 实物商品（默认）。 */
    static final String ITEM_TYPE_PRODUCT = "PRODUCT";
    /** 服务（人员的服务）。 */
    static final String ITEM_TYPE_SERVICE = "SERVICE";
    /** 可关联的服务人员资源类型（res_resource.resource_type）。 */
    static final String SERVER_RESOURCE_TYPE = "KTV_SERVER";
    /** 资源启用状态（res_resource.status）。 */
    static final String RESOURCE_STATUS_ENABLED = "ENABLED";

    private final ProductMapper productMapper;
    private final CatalogItemMapper catalogItemMapper;
    private final InventoryMaterialMapper materialMapper;
    private final AuditClient auditClient;
    private final ResourceStateClient resourceStateClient;

    @Autowired
    public ProductApplicationService(ProductMapper productMapper, CatalogItemMapper catalogItemMapper,
                                     InventoryMaterialMapper materialMapper, AuditClient auditClient,
                                     ResourceStateClient resourceStateClient) {
        this.productMapper = productMapper;
        this.catalogItemMapper = catalogItemMapper;
        this.materialMapper = materialMapper;
        this.auditClient = auditClient;
        this.resourceStateClient = resourceStateClient;
    }

    /** 兼容既有单测装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
    public ProductApplicationService(ProductMapper productMapper, CatalogItemMapper catalogItemMapper,
                                     InventoryMaterialMapper materialMapper, AuditClient auditClient) {
        this(productMapper, catalogItemMapper, materialMapper, auditClient, null);
    }

    /** 兼容既有单测装配：不传审计客户端与服务人员校验客户端时两者都关闭（服务型商品此时无法保存）。 */
    public ProductApplicationService(ProductMapper productMapper, CatalogItemMapper catalogItemMapper,
                                     InventoryMaterialMapper materialMapper) {
        this(productMapper, catalogItemMapper, materialMapper, AuditClient.disabled(), null);
    }

    /**
     * 商品增删改/上下架留痕：商品是点单目录与售卖价格的来源，改价/上下架必须可回溯。
     * 幂等键取「动作 + 商品ID + 更新时间」，保证同一状态变更重复上报只留一条。
     */
    private void recordProductAudit(String action, ProductPo product) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action(action)
                .resourceType("ord_product").resourceId(String.valueOf(product.getId()))
                .resourceName(product.getName())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson("{\"storeId\":" + product.getStoreId() + ",\"productCode\":\""
                        + product.getProductCode() + "\",\"salePrice\":" + product.getSalePrice()
                        + ",\"status\":\"" + product.getStatus() + "\",\"stockControlled\":"
                        + product.getStockControlled() + ",\"materialId\":" + product.getMaterialId()
                        + ",\"itemType\":\"" + effectiveItemType(product) + "\",\"serverResourceId\":"
                        + product.getServerResourceId() + "}")
                .build());
    }

    /**
     * 商品列表（当前门店，按 {@code sort_order, id} 升序）。
     *
     * <p><b>时间区间</b>：{@code range} 是按**商品创建时间** {@code ord_product.created_at} 的闭区间
     * （统一 {@code from}/{@code to} 口径，见 {@link TimeRangeParams}）；为空 = 不筛。
     * 落在 {@code created_at} 列上、不用函数包裹，保持索引可用；仅新增筛选，排序与既有参数不变。
     *
     * <p>服务型商品的「服务人员名称」随响应带出：一次读取本门店 KTV_SERVER 资源后按 id 回填，
     * 资源服务不可达时名称降级为 null（不阻断列表，列表仍能看到 serverResourceId）。
     */
    public List<ProductPo> list(Long storeId, String status, TimeRange range) {
        TenantContext context = context();
        if (storeId != null && !storeId.equals(context.storeId())) {
            throw new BusinessException("STORE_SCOPE_DENIED", "无权访问该门店商品");
        }
        List<ProductPo> products = productMapper.selectList(new LambdaQueryWrapper<ProductPo>()
                .eq(ProductPo::getTenantId, context.tenantId())
                .eq(storeId != null, ProductPo::getStoreId, storeId)
                .eq(status != null && !status.isBlank(), ProductPo::getStatus, status)
                .ge(range != null && range.hasFrom(), ProductPo::getCreatedAt,
                        range == null ? null : range.fromInclusive())
                .le(range != null && range.hasTo(), ProductPo::getCreatedAt,
                        range == null ? null : range.toInclusive())
                .orderByAsc(ProductPo::getSortOrder).orderByAsc(ProductPo::getId));
        return attachServerNames(products, context);
    }

    /** 服务人员名称批量回填（只读展示用：失败一律降级为 null，绝不阻断商品列表）。 */
    private List<ProductPo> attachServerNames(List<ProductPo> products, TenantContext context) {
        if (products == null || products.isEmpty()) {
            return products;
        }
        if (products.stream().noneMatch(product -> product.getServerResourceId() != null)) {
            return products;
        }
        Map<Long, String> names = new HashMap<>();
        try {
            if (resourceStateClient != null) {
                for (ServerSnapshot server : resourceStateClient.resources(SERVER_RESOURCE_TYPE, context.storeId())) {
                    if (server != null && server.resourceId() != null) {
                        names.put(server.resourceId(), server.name());
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 读路径降级：名称缺失不影响 serverResourceId 与其它字段的展示
        }
        for (ProductPo product : products) {
            if (product.getServerResourceId() != null) {
                product.setServerResourceName(names.get(product.getServerResourceId()));
            }
        }
        return products;
    }

    @Transactional
    public ProductPo create(ProductCommand command) {
        try {
            return doCreate(command);
        } catch (RuntimeException failure) {
            // 新建商品失败留痕（编码/名称非法、关联物料无效、落库失败）：与成功同码 product.create。
            recordProductFailure("product.create", null, command == null ? null : command.productCode(), failure);
            throw translateServerResourceConflict(failure);
        }
    }

    private ProductPo doCreate(ProductCommand command) {
        TenantContext context = context();
        requireStore(command.storeId(), context);
        if (command.productCode() == null || command.productCode().isBlank() || command.name() == null || command.name().isBlank()) {
            throw new BusinessException("PRODUCT_INVALID", "商品编码和名称不能为空");
        }
        if (command.salePrice() == null || command.salePrice().signum() <= 0) throw new BusinessException("PRODUCT_INVALID", "售价必须大于0");
        String itemType = requireItemType(command.itemType(), ITEM_TYPE_PRODUCT);
        boolean service = ITEM_TYPE_SERVICE.equals(itemType);
        // 服务「不占库存」：既不要求关联仓库商品，也不接受物料关联（服务只有人员）。
        if (!service && Boolean.TRUE.equals(command.stockControlled()) && command.materialId() == null) {
            throw new BusinessException("PRODUCT_INVALID", "实物商品必须关联仓库商品");
        }
        String description = ItemDescriptions.normalize(command.description(), "PRODUCT_INVALID");
        // 服务型商品：服务人员必填 + 必须存在/是 KTV_SERVER/同门店/启用（失败关闭，见 ResourceStateClient）。
        // 实物商品：忽略服务人员字段（serverResourceId 落库为 NULL），不做任何资源域调用。
        ServerSnapshot server = service ? requireServiceServer(command.serverResourceId(), null, context) : null;
        if (!service && command.materialId() != null) {
            requireMaterial(command.materialId(), context);
        }
        ProductPo product = new ProductPo();
        product.setTenantId(context.tenantId()); product.setStoreId(command.storeId()); product.setProductCode(command.productCode().trim());
        product.setName(command.name().trim()); product.setCategory(defaultValue(command.category(), "其他"));
        product.setItemType(itemType);
        product.setUnit(defaultValue(command.unit(), "份")); product.setSalePrice(command.salePrice());
        product.setMaterialId(service ? null : command.materialId());
        product.setServerResourceId(service ? command.serverResourceId() : null);
        product.setStockControlled(!service && (command.stockControlled() == null || command.stockControlled()));
        product.setStatus("DRAFT");
        product.setSortOrder(command.sortOrder() == null ? 0 : command.sortOrder()); product.setDescription(description);
        applyImages(product, command);
        product.setVersion(0); product.setCreatedAt(LocalDateTime.now()); product.setUpdatedAt(LocalDateTime.now()); productMapper.insert(product);
        if (server != null) product.setServerResourceName(server.name());
        syncCatalog(product);
        recordProductAudit("product.create", product);
        return product;
    }

    /**
     * 编辑商品。materialId 为 null 表示「不改关联」（部分更新语义），因此清空关联只能在
     * 「实物商品」开关关闭时生效；只要商品是实物（占用库存），就必须有 materialId，否则 400。
     *
     * <p>类型切换（{@code itemType}）：
     * <ul>
     *   <li>切到 SERVICE：必须给出服务人员（命令未带时沿用既有 {@code server_resource_id}），并重新校验其归属与状态；</li>
     *   <li>切到 PRODUCT：清空 {@code server_resource_id}（实物商品不关联服务人员），库存/物料校验回到原口径；</li>
     *   <li>命令里 {@code serverResourceId} 为 null 且商品仍是 SERVICE：沿用既有服务人员（部分更新语义）。</li>
     * </ul>
     */
    @Transactional
    public ProductPo update(Long id, ProductCommand command) {
        try {
            return doUpdate(id, command);
        } catch (RuntimeException failure) {
            // 编辑商品失败留痕（商品不存在/越权门店/关联物料无效/落库失败）：与成功同码 product.update。
            recordProductFailure("product.update", id, null, failure);
            throw translateServerResourceConflict(failure);
        }
    }

    /**
     * 并发兜底：唯一键 {@code uk_ord_product_server_resource} 冲突（两个请求同时把同一服务人员
     * 关联到不同服务商品）时，把数据库层的 DuplicateKeyException 翻译成与前置检查同一句中文 409，
     * 而不是让运营看到 500。
     */
    private static RuntimeException translateServerResourceConflict(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.DuplicateKeyException) {
                String message = cause.getMessage() == null ? "" : cause.getMessage();
                if (message.contains("uk_ord_product_server_resource")) {
                    return new BusinessException("SERVER_RESOURCE_IN_USE", "该服务人员已被其他服务商品关联，请刷新后重试");
                }
            }
        }
        return failure;
    }

    private ProductPo doUpdate(Long id, ProductCommand command) {
        ProductPo product = find(id);
        TenantContext context = context();
        boolean descriptionProvided = command.description() != null;
        String description = descriptionProvided ? ItemDescriptions.normalize(command.description(), "PRODUCT_INVALID") : null;
        String itemType = command.itemType() == null ? effectiveItemType(product) : requireItemType(command.itemType(), null);
        boolean service = ITEM_TYPE_SERVICE.equals(itemType);
        Long requestedServerId = command.serverResourceId() != null
                ? command.serverResourceId()
                : (service ? product.getServerResourceId() : null);
        ServerSnapshot server = null;
        if (service) {
            server = requireServiceServer(requestedServerId, product.getId(), context);
        }
        boolean stockControlled = service ? false
                : (command.stockControlled() == null ? Boolean.TRUE.equals(product.getStockControlled()) : command.stockControlled());
        Long effectiveMaterialId = service ? null
                : (command.materialId() == null ? product.getMaterialId() : command.materialId());
        if (!service && stockControlled && effectiveMaterialId == null) {
            throw new BusinessException("PRODUCT_INVALID", "实物商品必须关联仓库商品");
        }
        if (command.name() != null && !command.name().isBlank()) product.setName(command.name().trim());
        if (command.category() != null && !command.category().isBlank()) product.setCategory(command.category().trim());
        if (command.unit() != null && !command.unit().isBlank()) product.setUnit(command.unit().trim());
        if (command.salePrice() != null) { if (command.salePrice().signum() <= 0) throw new BusinessException("PRODUCT_INVALID", "售价必须大于0"); product.setSalePrice(command.salePrice()); }
        product.setItemType(itemType);
        if (service) {
            product.setServerResourceId(requestedServerId);
            product.setServerResourceName(server == null ? null : server.name());
            // 服务不占库存：切换成服务时强制清掉库存开关与物料关联，避免留下「服务却扣库存」的脏口径。
            product.setStockControlled(false);
            product.setMaterialId(null);
        } else {
            // 实物商品忽略服务人员字段：从服务切回实物时同步清空，避免残留孤儿关联占用唯一键。
            product.setServerResourceId(null);
            product.setServerResourceName(null);
            if (command.stockControlled() != null) product.setStockControlled(command.stockControlled());
            if (command.materialId() != null) {
                requireMaterial(command.materialId(), context);
                product.setMaterialId(command.materialId());
            }
        }
        if (descriptionProvided) product.setDescription(description);
        if (command.imageUrls() != null) applyImages(product, command);
        product.setUpdatedAt(LocalDateTime.now()); productMapper.updateById(product); syncCatalog(product);
        recordProductAudit("product.update", product);
        return product;
    }

    @Transactional
    public ProductPo onShelf(Long id) {
        try {
            ProductPo product = find(id);
            // 服务型商品上架前必须仍绑着服务人员（服务无人可派就不能出现在点单目录）。
            if (ITEM_TYPE_SERVICE.equals(effectiveItemType(product)) && product.getServerResourceId() == null) {
                throw new BusinessException("PRODUCT_SERVICE_SERVER_REQUIRED", "服务商品必须关联服务人员");
            }
            if (Boolean.TRUE.equals(product.getStockControlled())) {
                if (product.getMaterialId() == null) throw new BusinessException("PRODUCT_INVALID", "实物商品必须关联仓库商品");
                InventoryMaterialPo material = materialMapper.selectOne(new LambdaQueryWrapper<InventoryMaterialPo>()
                        .eq(InventoryMaterialPo::getTenantId, product.getTenantId()).eq(InventoryMaterialPo::getStoreId, product.getStoreId())
                        .eq(InventoryMaterialPo::getId, product.getMaterialId()).eq(InventoryMaterialPo::getStatus, "ACTIVE"));
                if (material == null) throw new BusinessException("PRODUCT_NOT_READY_FOR_SHELF", "关联物料不存在或已停用");
            }
            product.setStatus("ON_SHELF"); product.setUpdatedAt(LocalDateTime.now()); productMapper.updateById(product); syncCatalog(product);
            // 上架此前只有目录同步、没有操作留痕：上架即进入可售卖目录，必须可回溯（沿用既有动作码 product.publish）。
            recordProductAction("product.publish", "商品上架", product);
            return product;
        } catch (RuntimeException failure) {
            recordProductFailure("product.publish", id, null, failure);
            throw failure;
        }
    }

    @Transactional
    public ProductPo offShelf(Long id) {
        try {
            ProductPo product = find(id);
            product.setStatus("OFF_SHELF"); product.setUpdatedAt(LocalDateTime.now()); productMapper.updateById(product); syncCatalog(product);
            // 下架同样此前没有操作留痕（沿用既有动作码 product.unpublish）。
            recordProductAction("product.unpublish", "商品下架", product);
            return product;
        } catch (RuntimeException failure) {
            recordProductFailure("product.unpublish", id, null, failure);
            throw failure;
        }
    }

    /**
     * 商品写操作失败留痕：动作码与成功路径同码，{@code result=FAILURE} + 稳定 errorCode。
     *
     * <p>审计只走 {@link AuditClient#recordAsync}（失败仅 WARN），业务异常原样抛出：留痕不改变业务结果。
     * 不带幂等键（重复失败各自留痕，也不覆盖成功路径的稳定键）；detail 只放定位 ID 与商品编码，
     * <b>不含</b>售价/成本等金额。
     */
    private void recordProductFailure(String action, Long productId, String productCode, RuntimeException failure) {
        TenantContext context = TenantContextHolder.get();
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(context == null ? null : context.tenantId())
                .storeId(context == null ? null : context.storeId())
                .action(action)
                .resourceType("ord_product")
                .resourceId(productId == null ? null : String.valueOf(productId))
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"productId\":" + productId + ",\"productCode\":" + jsonText(productCode) + "}")
                .build());
    }

    /** 最小 JSON 字符串转义（商品编码来自运营输入，未转义会拼出非法 JSON 丢审计）。 */
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

    /** 上下架成功留痕（欠补齐动作）：与失败同码，detail 只放状态与检索字段，不含金额。 */
    private void recordProductAction(String action, String actionLabel, ProductPo product) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(product.getTenantId())
                .storeId(product.getStoreId())
                .action(action)
                .actionLabel(actionLabel)
                .resourceType("ord_product").resourceId(String.valueOf(product.getId()))
                .resourceName(product.getName())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson("{\"storeId\":" + product.getStoreId() + ",\"status\":\""
                        + product.getStatus() + "\"}")
                .build());
    }

    /**
     * 目录项与商品保持一致（点单目录要能看图）。
     * 图片镜像规则：商品自己的图优先；商品无图时退回其关联物料的图（物料加项场景图片只维护在物料上）。
     * 商品未上架时目录项为 INACTIVE，不会出现在点单列表里。
     *
     * <p><b>stock_controlled 必须跟着商品回写</b>（F3）：目录项该列是「是否扣库存」的查询缓存，
     * 商品改了「实物商品」开关或换了关联物料之后，点单列表/加项都要立刻看到新口径，否则会出现
     * 「商品是实物但目录项标 0」→ 加项不扣库存、库存 0 仍可点的超卖。权威口径仍是商品 + 物料，
     * 该列只作缓存与兜底（读取侧见 CatalogController/OrderItemController）。
     *
     * <p><b>item_type 必须跟着商品回写</b>（第 4 点）：服务型商品同步出的目录项必须是 SERVICE，
     * 这样「点单/加项弹窗」里的服务项与「服务人员点单」共用同一份 {@code item_type=SERVICE} 目录项，
     * 不再造第二套；服务项单价沿用商品售价（现有同步逻辑不变）。
     */
    private void syncCatalog(ProductPo product) {
        CatalogItemPo catalog = findLinkedCatalogItem(product);
        catalog.setTenantId(product.getTenantId()); catalog.setStoreId(product.getStoreId()); catalog.setCategory(product.getCategory());
        catalog.setItemType(effectiveItemType(product));
        catalog.setName(product.getName()); catalog.setUnit(product.getUnit()); catalog.setUnitPrice(product.getSalePrice()); catalog.setDescription(product.getDescription());
        catalog.setStatus("ON_SHELF".equals(product.getStatus()) ? "ACTIVE" : "INACTIVE"); catalog.setStockControlled(Boolean.TRUE.equals(product.getStockControlled()));
        catalog.setProductId(product.getId()); catalog.setSortOrder(product.getSortOrder()); catalog.setUpdatedAt(LocalDateTime.now());
        applyCatalogImages(catalog, product);
        if (catalog.getId() == null) { catalog.setCreatedAt(LocalDateTime.now()); catalogItemMapper.insert(catalog); product.setCatalogItemId(catalog.getId()); productMapper.updateById(product); }
        else catalogItemMapper.updateById(catalog);
    }

    /**
     * 目录项定位：优先商品回指（ord_product.catalog_item_id），其次目录项侧回指（ord_catalog_item.product_id）。
     * 历史数据可能只有单向关联，这里复用已有目录项而不是再建一条，避免同一个商品出现两套目录/两套库存口径。
     */
    private CatalogItemPo findLinkedCatalogItem(ProductPo product) {
        if (product.getCatalogItemId() != null) {
            CatalogItemPo byId = catalogItemMapper.selectOne(new LambdaQueryWrapper<CatalogItemPo>()
                    .eq(CatalogItemPo::getTenantId, product.getTenantId()).eq(CatalogItemPo::getStoreId, product.getStoreId())
                    .eq(CatalogItemPo::getId, product.getCatalogItemId()));
            if (byId != null) {
                return byId;
            }
        }
        if (product.getId() == null) {
            return new CatalogItemPo();
        }
        CatalogItemPo byProductId = catalogItemMapper.selectOne(new LambdaQueryWrapper<CatalogItemPo>()
                .eq(CatalogItemPo::getTenantId, product.getTenantId()).eq(CatalogItemPo::getStoreId, product.getStoreId())
                .eq(CatalogItemPo::getProductId, product.getId()));
        return byProductId == null ? new CatalogItemPo() : byProductId;
    }

    /** 目录项图片 = 商品图，商品无图时回退到关联物料图（都无图则清空，前端用占位图）。 */
    private void applyCatalogImages(CatalogItemPo catalog, ProductPo product) {
        List<String> urls = product.getImageUrls();
        String main = product.getMainImageUrl();
        if ((urls == null || urls.isEmpty()) && product.getMaterialId() != null) {
            InventoryMaterialPo material = materialMapper.selectById(product.getMaterialId());
            if (material != null && material.getImageUrls() != null && !material.getImageUrls().isEmpty()) {
                urls = material.getImageUrls();
                main = material.getMainImageUrl();
            }
        }
        catalog.setImageUrls(urls == null ? List.of() : urls);
        catalog.setMainImageUrl(main);
    }

    private ProductPo find(Long id) { ProductPo p = productMapper.selectById(id); if (p == null || !p.getTenantId().equals(context().tenantId()) || !p.getStoreId().equals(context().storeId())) throw new BusinessException("PRODUCT_NOT_FOUND", "商品不存在"); return p; }
    private static void requireStore(Long storeId, TenantContext context) { if (storeId == null || context.storeId() == null || !storeId.equals(context.storeId())) throw new BusinessException("STORE_SCOPE_DENIED", "无权操作该门店"); }
    private void requireMaterial(Long materialId, TenantContext context) {
        InventoryMaterialPo material = materialMapper.selectOne(new LambdaQueryWrapper<InventoryMaterialPo>()
                .eq(InventoryMaterialPo::getTenantId, context.tenantId()).eq(InventoryMaterialPo::getStoreId, context.storeId())
                .eq(InventoryMaterialPo::getId, materialId));
        if (material == null || !"ACTIVE".equals(material.getStatus())) {
            throw new BusinessException("MATERIAL_NOT_FOUND", "关联物料不存在或已停用");
        }
    }
    private TenantContext context() { TenantContext c = TenantContextHolder.get(); if (c == null || c.tenantId() <= 0 || c.storeId() == null) throw new BusinessException("TENANT_CONTEXT_REQUIRED", "缺少有效门店上下文"); return c; }
    private static String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    /** 商品图片：最多 9 张，主图必须来自列表（列表非空时恰好一张主图）。 */
    private static void applyImages(ProductPo product, ProductCommand command) {
        ItemImages.Images images = ItemImages.normalize(command.imageUrls(), command.mainImageUrl(), "PRODUCT_INVALID");
        product.setImageUrls(images.urls()); product.setMainImageUrl(images.mainImageUrl());
    }

    /**
     * 商品类型的权威取值（兼容历史行 {@code item_type} 为空的情况：一律按 PRODUCT 处理）。
     */
    static String effectiveItemType(ProductPo product) {
        String type = product == null ? null : product.getItemType();
        return ITEM_TYPE_SERVICE.equalsIgnoreCase(type == null ? "" : type.trim()) ? ITEM_TYPE_SERVICE : ITEM_TYPE_PRODUCT;
    }

    /**
     * 校验并归一化请求里的商品类型：只接受 PRODUCT / SERVICE，其它值（含乱写的英文枚举）一律 400。
     *
     * @param fallback 请求未带该字段时的取值（创建时 PRODUCT；编辑时由调用方先算好，传 null 表示必填）
     */
    private static String requireItemType(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            if (fallback != null) {
                return fallback;
            }
            throw new BusinessException("PRODUCT_ITEM_TYPE_INVALID", "商品类型只能是实物商品（PRODUCT）或服务（SERVICE）");
        }
        String type = raw.trim().toUpperCase();
        if (!ITEM_TYPE_PRODUCT.equals(type) && !ITEM_TYPE_SERVICE.equals(type)) {
            throw new BusinessException("PRODUCT_ITEM_TYPE_INVALID", "商品类型只能是实物商品（PRODUCT）或服务（SERVICE）");
        }
        return type;
    }

    /**
     * 校验服务型商品关联的服务人员：必须存在、必须是 KTV_SERVER、必须属于当前门店、必须启用中，
     * 且同一个服务人员在同一门店下不能被两个服务商品同时占用。
     *
     * <p>跨域读走 {@link ResourceStateClient#requireServer} 的**失败关闭**语义
     * （资源服务不可达 → {@code SERVER_RESOURCE_UNAVAILABLE}，绝不静默放过）；
     * 唯一性先做一次可读的应用层检查（给出占用方商品名），数据库唯一键
     * {@code uk_ord_product_server_resource} 是并发下的最后一道防线。
     *
     * @param selfProductId 编辑时传当前商品 id（排除自己）；创建时传 null
     */
    private ServerSnapshot requireServiceServer(Long serverResourceId, Long selfProductId, TenantContext context) {
        if (serverResourceId == null) {
            throw new BusinessException("PRODUCT_SERVICE_SERVER_REQUIRED", "服务商品必须关联服务人员");
        }
        if (resourceStateClient == null) {
            throw new BusinessException("SERVER_RESOURCE_UNAVAILABLE", "服务人员服务未装配，无法校验服务人员，请稍后重试");
        }
        ServerSnapshot server = resourceStateClient.requireServer(serverResourceId);
        if (server == null || server.resourceId() == null) {
            throw new BusinessException("SERVER_RESOURCE_NOT_FOUND", "服务人员不存在或已被删除");
        }
        if (!SERVER_RESOURCE_TYPE.equalsIgnoreCase(server.resourceType() == null ? "" : server.resourceType().trim())) {
            throw new BusinessException("SERVER_RESOURCE_TYPE_INVALID", "所选资源不是服务人员，请重新选择");
        }
        if (server.storeId() != null && !server.storeId().equals(context.storeId())) {
            throw new BusinessException("SERVER_RESOURCE_STORE_MISMATCH", "服务人员不属于当前门店，请切换门店后重新选择");
        }
        if (!RESOURCE_STATUS_ENABLED.equalsIgnoreCase(server.status() == null ? "" : server.status().trim())) {
            throw new BusinessException("SERVER_RESOURCE_DISABLED", "服务人员已停用，请先在资源管理里启用后再关联");
        }
        ProductPo occupied = productMapper.selectOne(new LambdaQueryWrapper<ProductPo>()
                .eq(ProductPo::getTenantId, context.tenantId())
                .eq(ProductPo::getStoreId, context.storeId())
                .eq(ProductPo::getItemType, ITEM_TYPE_SERVICE)
                .eq(ProductPo::getServerResourceId, serverResourceId)
                .ne(selfProductId != null, ProductPo::getId, selfProductId)
                .last("LIMIT 1"));
        if (occupied != null) {
            throw new BusinessException("SERVER_RESOURCE_IN_USE",
                    "该服务人员已关联服务商品「%s」，请先解除原关联".formatted(occupied.getName()));
        }
        return server;
    }

    /**
     * 商品创建/编辑命令。
     *
     * <p>{@code itemType} / {@code serverResourceId} 追加在末尾：{@code itemType} 为空按实物商品处理，
     * 编辑态里两个字段为 null 都表示「本次不改类型 / 不改关联」。12 参的旧构造器保留给既有调用方与单测。
     */
    public record ProductCommand(Long storeId, String productCode, String name, String category, String unit,
                                 java.math.BigDecimal salePrice, Long materialId, Boolean stockControlled,
                                 Integer sortOrder, String description, java.util.List<String> imageUrls,
                                 String mainImageUrl, String itemType, Long serverResourceId) {

        /** 兼容既有调用方/单测：不传商品类型与服务人员时按实物商品（PRODUCT）处理。 */
        public ProductCommand(Long storeId, String productCode, String name, String category, String unit,
                             java.math.BigDecimal salePrice, Long materialId, Boolean stockControlled,
                             Integer sortOrder, String description, java.util.List<String> imageUrls,
                             String mainImageUrl) {
            this(storeId, productCode, name, category, unit, salePrice, materialId, stockControlled,
                    sortOrder, description, imageUrls, mainImageUrl, null, null);
        }
    }
}
