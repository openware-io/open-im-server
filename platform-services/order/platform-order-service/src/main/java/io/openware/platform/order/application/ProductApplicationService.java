package io.openware.platform.order.application;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.order.api.dto.ProductView;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.model.Product;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.AuditSnapshot;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.Persisted;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.domain.inventory.model.InventoryMaterial;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.infra.client.ResourceStateClient.ServerSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;

/**
 * 商品（实物 / 服务）的创建、编辑、上下架与点单目录同步。
 *
 * <p><b>本次迁移</b>：商品与目录项改经领域端口（{@code ProductRepository} /
 * {@code CatalogItemRepository}）读写；服务人员名称这一**展示字段**由
 * {@link ProductView#withServerResourceName} 在响应期回填——它不是列，不能进领域模型，
 * 更不能进任何列引用（对 {@code exist = false} 的字段生成列引用会在运行时抛
 * {@code can not find lambda cache for this property [...]}）。
 *
 * <p>跨子域读走库存子域的 {@link InventoryMaterialRepository} 端口：校验「关联物料存在且启用」与取物料
 * 图片（目录项图片回退）都要读 {@code ord_inventory_material}，迁移前直接持有
 * {@code InventoryMaterialMapper}；库存切片把端口建好后就换成了端口，本类不再依赖持久化实现。
 * 用法保持窄：只 {@code findById} / {@code findInStore}。
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

    private final ProductRepository productRepository;
    private final CatalogItemRepository catalogItemRepository;
    private final InventoryMaterialRepository materialRepository;
    private final AuditClient auditClient;
    private final ResourceStateClient resourceStateClient;

    @Autowired
    public ProductApplicationService(ProductRepository productRepository, CatalogItemRepository catalogItemRepository,
                                     InventoryMaterialRepository materialRepository, AuditClient auditClient,
                                     ResourceStateClient resourceStateClient) {
        this.productRepository = productRepository;
        this.catalogItemRepository = catalogItemRepository;
        this.materialRepository = materialRepository;
        this.auditClient = auditClient;
        this.resourceStateClient = resourceStateClient;
    }

    /** 兼容既有单测装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
    public ProductApplicationService(ProductRepository productRepository, CatalogItemRepository catalogItemRepository,
                                     InventoryMaterialRepository materialRepository, AuditClient auditClient) {
        this(productRepository, catalogItemRepository, materialRepository, auditClient, null);
    }

    /** 兼容既有单测装配：不传审计客户端与服务人员校验客户端时两者都关闭（服务型商品此时无法保存）。 */
    public ProductApplicationService(ProductRepository productRepository, CatalogItemRepository catalogItemRepository,
                                     InventoryMaterialRepository materialRepository) {
        this(productRepository, catalogItemRepository, materialRepository, AuditClient.disabled(), null);
    }

    /**
     * 商品增删改/上下架留痕：商品是点单目录与售卖价格的来源，改价/上下架必须可回溯。
     * 幂等键取「动作 + 商品ID + 更新时间」，保证同一状态变更重复上报只留一条。
     */
    private void recordProductAudit(String action, Product product) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action(action)
                .resourceType("ord_product").resourceId(String.valueOf(product.id()))
                .resourceName(product.name())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson("{\"storeId\":" + product.storeId() + ",\"productCode\":\""
                        + product.productCode() + "\",\"salePrice\":" + product.salePrice()
                        + ",\"status\":\"" + product.status() + "\",\"stockControlled\":"
                        + product.stockControlled() + ",\"materialId\":" + product.materialId()
                        + ",\"itemType\":\"" + product.effectiveItemType() + "\",\"serverResourceId\":"
                        + product.serverResourceId() + "}")
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
     * 该字段不是列，只存在于 {@link ProductView}。
     */
    public List<ProductView> list(Long storeId, String status, TimeRange range) {
        TenantContext context = context();
        if (storeId != null && !storeId.equals(context.storeId())) {
            throw new BusinessException("STORE_SCOPE_DENIED", "无权访问该门店商品");
        }
        List<Product> products = productRepository.find(ProductRepository.ProductQuery.inStore(
                context.tenantId(), storeId, blankToNull(status),
                range == null ? null : range.fromInclusive(),
                range == null ? null : range.toInclusive()));
        return attachServerNames(products, context);
    }

    /** 服务人员名称批量回填（只读展示用：失败一律降级为 null，绝不阻断商品列表）。 */
    private List<ProductView> attachServerNames(List<Product> products, TenantContext context) {
        List<ProductView> views = new ArrayList<>(products.size());
        for (Product product : products) {
            // 列表读路径由端口返回的聚合装配视图；审计快照传 null = 列表响应里这四个字段为 null
            // （原实现把 PO 直接序列化，列表里带的是行级审计时间）。这里刻意不为此把审计字段
            // 搬回领域模型，契约字段名不变、值可为空——见 ProductView 的说明。
            views.add(ProductView.from(product, (AuditSnapshot) null));
        }
        if (views.stream().noneMatch(view -> view.serverResourceId() != null)) {
            return views;
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
        }
        // degradation: 读路径降级，名称缺失不影响 serverResourceId 与其它字段的展示
        catch (RuntimeException ignored) {
            // 读路径降级：名称缺失不影响 serverResourceId 与其它字段的展示
        }
        List<ProductView> decorated = new ArrayList<>(views.size());
        for (ProductView view : views) {
            decorated.add(view.serverResourceId() == null ? view
                    : view.withServerResourceName(names.get(view.serverResourceId())));
        }
        return decorated;
    }

    @Transactional
    public ProductView create(ProductCommand command) {
        try {
            return doCreate(command);
        } catch (RuntimeException failure) {
            // 新建商品失败留痕（编码/名称非法、关联物料无效、落库失败）：与成功同码 product.create。
            recordProductFailure("product.create", null, command == null ? null : command.productCode(), failure);
            throw translateServerResourceConflict(failure);
        }
    }

    private ProductView doCreate(ProductCommand command) {
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
        ItemImages.Images images = ItemImages.normalize(command.imageUrls(), command.mainImageUrl(), "PRODUCT_INVALID");
        Product draft = Product.draft(context.tenantId(), command.storeId(), command.productCode().trim(),
                command.name().trim(), defaultValue(command.category(), "其他"), itemType,
                defaultValue(command.unit(), "份"), command.salePrice(),
                service ? null : command.materialId(),
                service ? command.serverResourceId() : null,
                !service && (command.stockControlled() == null || command.stockControlled()),
                command.sortOrder() == null ? 0 : command.sortOrder(), description,
                images.urls(), images.mainImageUrl());
        Persisted<Product> inserted = productRepository.insert(draft);
        // syncCatalog 负责目录项同步，并在新建目录项后把 catalog_item_id 回指写回商品；
        // 它返回回指已落库的商品，因此调用方/响应体不需要再读一次库。
        Product product = syncCatalog(inserted.aggregate());
        recordProductAudit("product.create", product);
        // 服务人员名称是展示字段：本次由调用方从 requireServiceServer 的快照补进视图，绝不落库。
        ProductView view = ProductView.from(product, inserted.audit());
        return server == null ? view : view.withServerResourceName(server.name());
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
    public ProductView update(Long id, ProductCommand command) {
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
            if (cause instanceof DuplicateKeyException) {
                String message = cause.getMessage() == null ? "" : cause.getMessage();
                if (message.contains("uk_ord_product_server_resource")) {
                    return new BusinessException("SERVER_RESOURCE_IN_USE", "该服务人员已被其他服务商品关联，请刷新后重试");
                }
            }
        }
        return failure;
    }

    private ProductView doUpdate(Long id, ProductCommand command) {
        Product product = find(id);
        TenantContext context = context();
        boolean descriptionProvided = command.description() != null;
        String description = descriptionProvided ? ItemDescriptions.normalize(command.description(), "PRODUCT_INVALID") : null;
        String itemType = command.itemType() == null ? product.effectiveItemType() : requireItemType(command.itemType(), null);
        boolean service = ITEM_TYPE_SERVICE.equals(itemType);
        Long requestedServerId = command.serverResourceId() != null
                ? command.serverResourceId()
                : (service ? product.serverResourceId() : null);
        ServerSnapshot server = null;
        if (service) {
            server = requireServiceServer(requestedServerId, product.id(), context);
        }
        boolean stockControlled = service ? false
                : (command.stockControlled() == null ? Boolean.TRUE.equals(product.stockControlled()) : command.stockControlled());
        Long effectiveMaterialId = service ? null
                : (command.materialId() == null ? product.materialId() : command.materialId());
        if (!service && stockControlled && effectiveMaterialId == null) {
            throw new BusinessException("PRODUCT_INVALID", "实物商品必须关联仓库商品");
        }
        if (command.salePrice() != null && command.salePrice().signum() <= 0) {
            throw new BusinessException("PRODUCT_INVALID", "售价必须大于0");
        }
        if (!service && command.materialId() != null) {
            requireMaterial(command.materialId(), context);
        }
        ItemImages.Images images = command.imageUrls() == null
                ? new ItemImages.Images(product.imagesOrEmpty(), product.mainImageUrl())
                : ItemImages.normalize(command.imageUrls(), command.mainImageUrl(), "PRODUCT_INVALID");
        // 部分更新语义：请求未带的字段沿用既有值；类型切换时按上文算出的权威值覆盖。
        Product merged = product.edited(
                replaceIfPresent(command.name(), product.name()),
                replaceIfPresent(command.category(), product.category()),
                replaceIfPresent(command.unit(), product.unit()),
                command.salePrice() != null ? command.salePrice() : product.salePrice(),
                itemType,
                service ? requestedServerId : null,
                stockControlled,
                effectiveMaterialId,
                descriptionProvided ? description : product.description(),
                images.urls(), images.mainImageUrl());
        Persisted<Product> persisted = productRepository.update(merged);
        Product updated = syncCatalog(persisted.aggregate());
        recordProductAudit("product.update", updated);
        return ProductView.from(updated, persisted.audit());
    }

    @Transactional
    public ProductView onShelf(Long id) {
        try {
            Product product = find(id);
            // 服务型商品上架前必须仍绑着服务人员（服务无人可派就不能出现在点单目录）。
            if (product.serviceItem() && product.serverResourceId() == null) {
                throw new BusinessException("PRODUCT_SERVICE_SERVER_REQUIRED", "服务商品必须关联服务人员");
            }
            if (Boolean.TRUE.equals(product.stockControlled())) {
                if (product.materialId() == null) throw new BusinessException("PRODUCT_INVALID", "实物商品必须关联仓库商品");
                if (!materialActive(product.materialId(), product.tenantId(), product.storeId())) {
                    throw new BusinessException("PRODUCT_NOT_READY_FOR_SHELF", "关联物料不存在或已停用");
                }
            }
            Persisted<Product> persisted = productRepository.update(
                    product.withStatus(Product.STATUS_ON_SHELF));
            Product published = syncCatalog(persisted.aggregate());
            // 上架此前只有目录同步、没有操作留痕：上架即进入可售卖目录，必须可回溯（沿用既有动作码 product.publish）。
            recordProductAction("product.publish", "商品上架", published);
            return ProductView.from(published, persisted.audit());
        } catch (RuntimeException failure) {
            recordProductFailure("product.publish", id, null, failure);
            throw failure;
        }
    }

    @Transactional
    public ProductView offShelf(Long id) {
        try {
            Product product = find(id);
            Persisted<Product> persisted = productRepository.update(
                    product.withStatus(Product.STATUS_OFF_SHELF));
            Product unpublished = syncCatalog(persisted.aggregate());
            // 下架同样此前没有操作留痕（沿用既有动作码 product.unpublish）。
            recordProductAction("product.unpublish", "商品下架", unpublished);
            return ProductView.from(unpublished, persisted.audit());
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
    private void recordProductAction(String action, String actionLabel, Product product) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(product.tenantId())
                .storeId(product.storeId())
                .action(action)
                .actionLabel(actionLabel)
                .resourceType("ord_product").resourceId(String.valueOf(product.id()))
                .resourceName(product.name())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson("{\"storeId\":" + product.storeId() + ",\"status\":\""
                        + product.status() + "\"}")
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
    private Product syncCatalog(Product product) {
        CatalogItem catalog = findLinkedCatalogItem(product);
        CatalogItem synced = catalog.syncedFromProduct(product.id(), product.tenantId(), product.storeId(),
                product.category(), product.effectiveItemType(), product.name(), product.unit(), product.salePrice(),
                product.description(), Product.STATUS_ON_SHELF.equals(product.status())
                        ? CatalogItem.STATUS_ACTIVE : CatalogItem.STATUS_INACTIVE,
                Boolean.TRUE.equals(product.stockControlled()), product.sortOrder());
        List<String> images = catalogImages(product);
        synced = synced.withImages(images, catalogImageMain(product, images));
        if (!synced.persisted()) {
            CatalogItem inserted = catalogItemRepository.insert(synced).aggregate();
            // 商品侧回指必须落库（ord_product.catalog_item_id 是权威关联方向），
            // 否则下次同步只能靠 ord_catalog_item.product_id 反查，历史脏数据下会建出第二条目录项。
            Product linked = product.withCatalogItemId(inserted.id());
            productRepository.update(linked);
            return linked;
        }
        catalogItemRepository.update(synced);
        return product;
    }

    /**
     * 目录项定位：优先商品回指（ord_product.catalog_item_id），其次目录项侧回指（ord_catalog_item.product_id）。
     * 历史数据可能只有单向关联，这里复用已有目录项而不是再建一条，避免同一个商品出现两套目录/两套库存口径。
     */
    private CatalogItem findLinkedCatalogItem(Product product) {
        if (product.catalogItemId() != null) {
            CatalogItem byId = catalogItemRepository.findById(product.catalogItemId()).orElse(null);
            if (byId != null && sameScope(byId, product)) {
                return byId;
            }
        }
        if (product.id() == null) {
            return CatalogItem.draft(product.tenantId(), product.storeId(), product.category(),
                    product.effectiveItemType(), product.name(), product.unit(), product.salePrice(),
                    product.description(), List.of(), null, product.sortOrder());
        }
        return catalogItemRepository.find(CatalogItemRepository.CatalogItemQuery.linkedToProduct(
                product.tenantId(), product.storeId(), product.id())).stream().findFirst()
            .orElseGet(() -> CatalogItem.draft(product.tenantId(), product.storeId(), product.category(),
                    product.effectiveItemType(), product.name(), product.unit(), product.salePrice(),
                    product.description(), List.of(), null, product.sortOrder()));
    }

    /** 目录项与商品必须同租户同门店：跨门店回指（脏数据）一律当「没有关联」处理，不覆盖别人的目录项。 */
    private static boolean sameScope(CatalogItem catalog, Product product) {
        return java.util.Objects.equals(catalog.tenantId(), product.tenantId())
                && java.util.Objects.equals(catalog.storeId(), product.storeId());
    }

    /** 目录项图片 = 商品图，商品无图时回退到关联物料图（都无图则清空，前端用占位图）。 */
    private List<String> catalogImages(Product product) {
        if (!product.imagesOrEmpty().isEmpty()) {
            return product.imagesOrEmpty();
        }
        if (product.materialId() == null) {
            return List.of();
        }
        InventoryMaterial material = materialRepository.findById(product.materialId()).orElse(null);
        if (material == null || material.imagesOrEmpty().isEmpty()) {
            return List.of();
        }
        return material.imagesOrEmpty();
    }

    /** 目录项主图与 {@link #catalogImages} 同源（商品主图；回退物料主图；都无则 null）。 */
    private String catalogImageMain(Product product, List<String> images) {
        if (!product.imagesOrEmpty().isEmpty()) {
            return product.mainImageUrl();
        }
        if (images.isEmpty() || product.materialId() == null) {
            return null;
        }
        return materialRepository.findById(product.materialId()).map(InventoryMaterial::mainImageUrl).orElse(null);
    }

    private Product find(Long id) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "商品不存在"));
        TenantContext context = context();
        if (context.tenantId() != product.tenantId()
                || !Objects.equals(context.storeId(), product.storeId())) {
            throw new BusinessException("PRODUCT_NOT_FOUND", "商品不存在");
        }
        return product;
    }

    private static void requireStore(Long storeId, TenantContext context) {
        if (storeId == null || context.storeId() == null || !storeId.equals(context.storeId())) {
            throw new BusinessException("STORE_SCOPE_DENIED", "无权操作该门店");
        }
    }

    /**
     * 关联物料必须存在且 ACTIVE（跨子域窄读：一次 {@code findInStore}）。
     *
     * <p>「存在且启用」是业务判断，落在应用层：端口只回答「这个门店里有没有这一行」，
     * 是否启用由 {@link InventoryMaterial#active()} 判定，错误码仍由本类给出。
     */
    private boolean materialActive(Long materialId, Long tenantId, Long storeId) {
        return materialRepository.findInStore(tenantId, storeId, materialId)
            .filter(InventoryMaterial::active)
            .isPresent();
    }

    private void requireMaterial(Long materialId, TenantContext context) {
        InventoryMaterial material = materialRepository
            .findInStore(context.tenantId(), context.storeId(), materialId)
            .orElse(null);
        if (material == null || !material.active()) {
            throw new BusinessException("MATERIAL_NOT_FOUND", "关联物料不存在或已停用");
        }
    }

    private TenantContext context() {
        TenantContext c = TenantContextHolder.get();
        if (c == null || c.tenantId() <= 0 || c.storeId() == null) {
            throw new BusinessException("TENANT_CONTEXT_REQUIRED", "缺少有效门店上下文");
        }
        return c;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 部分更新：请求带了非空值才替换（与既有 {@code if (command.x() != null && !isBlank()) product.setX(...)} 同口径）。 */
    private static String replaceIfPresent(String requested, String existing) {
        return requested == null || requested.isBlank() ? existing : requested.trim();
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
        Product occupied = productRepository.findServerResourceOccupant(context.tenantId(), context.storeId(),
                ITEM_TYPE_SERVICE, serverResourceId, selfProductId).orElse(null);
        if (occupied != null) {
            throw new BusinessException("SERVER_RESOURCE_IN_USE",
                    "该服务人员已关联服务商品「%s」，请先解除原关联".formatted(occupied.name()));
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
