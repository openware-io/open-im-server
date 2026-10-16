package io.openware.platform.order.application;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.dto.ProductCategoryView;
import io.openware.platform.order.domain.catalog.model.ProductCategory;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.Persisted;
import io.openware.platform.order.domain.catalog.repository.ProductCategoryRepository;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 商品分类字典（按门店维护）。
 *
 * <p><b>本次迁移</b>：分类、商品、目录项三张表都改经领域端口读写
 * （{@code ProductCategoryRepository} / {@code ProductRepository} / {@code CatalogItemRepository}），
 * 响应体由 {@link ProductCategoryView} 装配（JSON 字段名与原持久化对象一致）。
 * 「改名同步」与「被引用禁止删除」两条批量语义留在应用层编排，各自由端口上的**单条条件 UPDATE**
 * 与**计数查询**落实——不逐行读改写，避免 N 次写与并发覆盖。
 *
 * <p>商品与目录项仍以「分类名称」冗余引用（不引入外键，避免动 ord_product 既有列），
 * 所以改名采用「同步改名」方案：同一事务内把该门店下引用旧名的商品 category 与
 * 点单目录项 category 一起改成新名，保证分类列表、商品列表、点单页分组三处一致。
 * 被商品引用的分类禁止删除，避免商品挂在不存在的分类上。</p>
 *
 * <p>审计（2026-09 补齐）：分类名是商品与点单目录的分组依据，改名会连带改写引用，
 * 因此新建/改名/删除与商品、房型同级，必须可回溯——此前
 * {@code AuditActions} 已登记 {@code product.category.*} 标签，但没有任何调用点上报，
 * 后台删除分类在操作日志里查不到。成功与失败（重名、仍被商品引用、不存在）都留痕。</p>
 */
@Service
public class ProductCategoryApplicationService {
    public static final int MAX_NAME_LENGTH = ProductCategory.MAX_NAME_LENGTH;
    private static final String STATUS_ACTIVE = ProductCategory.STATUS_ACTIVE;
    private static final String STATUS_DISABLED = ProductCategory.STATUS_DISABLED;

    /** 审计资源类型与动作码：与 {@code AuditActions} 的稳定码一致（前端按码筛选）。 */
    private static final String AUDIT_RESOURCE_TYPE = "ord_product_category";
    private static final String ACTION_CREATE = "product.category.create";
    private static final String ACTION_UPDATE = "product.category.update";
    private static final String ACTION_DELETE = "product.category.delete";

    private final ProductCategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final CatalogItemRepository catalogItemRepository;
    private final AuditClient auditClient;

    @Autowired
    public ProductCategoryApplicationService(ProductCategoryRepository categoryRepository,
                                             ProductRepository productRepository,
                                             CatalogItemRepository catalogItemRepository,
                                             AuditClient auditClient) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.catalogItemRepository = catalogItemRepository;
        this.auditClient = auditClient;
    }

    /** 兼容既有单测装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
    public ProductCategoryApplicationService(ProductCategoryRepository categoryRepository,
                                             ProductRepository productRepository,
                                             CatalogItemRepository catalogItemRepository) {
        this(categoryRepository, productRepository, catalogItemRepository, AuditClient.disabled());
    }

    /** 分类列表：按 sort_order、name 排序；status 传入时只看该状态（如 status=ACTIVE 只看启用）。 */
    public List<ProductCategoryView> list(Long storeId, String status) {
        TenantContext context = context();
        if (storeId != null && !storeId.equals(context.storeId())) {
            throw new BusinessException("STORE_SCOPE_DENIED", "无权访问该门店分类");
        }
        List<ProductCategory> categories = categoryRepository.find(context.tenantId(), context.storeId(),
                blankToNull(status));
        List<ProductCategoryView> views = new ArrayList<>(categories.size());
        for (ProductCategory category : categories) {
            // 列表读路径没有审计快照：这四个响应字段为 null（字段名不变，见 ProductCategoryView）。
            views.add(ProductCategoryView.from(category, null));
        }
        return views;
    }

    @Transactional
    public ProductCategoryView create(CategoryCommand command) {
        try {
            Persisted<ProductCategory> persisted = doCreate(command);
            recordAudit(ACTION_CREATE, persisted.aggregate());
            return ProductCategoryView.from(persisted.aggregate(), persisted.audit());
        } catch (RuntimeException failure) {
            recordFailure(ACTION_CREATE, null, failure);
            throw failure;
        }
    }

    private Persisted<ProductCategory> doCreate(CategoryCommand command) {
        TenantContext context = context();
        String name = requireName(command.name());
        if (findByName(context, name) != null) {
            throw new BusinessException("PRODUCT_CATEGORY_DUPLICATED", "分类名称已存在：" + name);
        }
        // 审计列（created_at/updated_at）由适配器按落库时刻填充，领域模型不承载它们。
        return categoryRepository.insert(ProductCategory.draft(context.tenantId(), context.storeId(), name,
                command.sortOrder() == null ? 0 : command.sortOrder()));
    }

    @Transactional
    public ProductCategoryView update(Long id, CategoryCommand command) {
        try {
            Persisted<ProductCategory> persisted = doUpdate(id, command);
            recordAudit(ACTION_UPDATE, persisted.aggregate());
            return ProductCategoryView.from(persisted.aggregate(), persisted.audit());
        } catch (RuntimeException failure) {
            recordFailure(ACTION_UPDATE, id, failure);
            throw failure;
        }
    }

    private Persisted<ProductCategory> doUpdate(Long id, CategoryCommand command) {
        TenantContext context = context();
        ProductCategory category = find(id, context);
        String name = category.name();
        if (command.name() != null && !command.name().isBlank()) {
            String requested = requireName(command.name());
            if (!requested.equals(category.name())) {
                ProductCategory duplicated = findByName(context, requested);
                if (duplicated != null && !duplicated.id().equals(category.id())) {
                    throw new BusinessException("PRODUCT_CATEGORY_DUPLICATED", "分类名称已存在：" + requested);
                }
                name = requested;
            }
        }
        Integer sortOrder = command.sortOrder() != null ? command.sortOrder() : category.sortOrder();
        String status = command.status() != null && !command.status().isBlank()
                ? requireStatus(command.status()) : category.status();
        ProductCategory merged = category.edited(name, sortOrder, status);
        if (!name.equals(category.name())) {
            renameReferences(category, name);
        }
        return categoryRepository.update(merged);
    }

    /** 删除分类：仍被商品引用时禁止删除（否则商品会挂在列表里不存在的分类上）。 */
    @Transactional
    public void delete(Long id) {
        try {
            recordAudit(ACTION_DELETE, doDelete(id));
        } catch (RuntimeException failure) {
            // 失败出口留痕（分类不存在 / 仍被商品引用 PRODUCT_CATEGORY_IN_USE）：审计只 WARN，异常原样抛出。
            recordFailure(ACTION_DELETE, id, failure);
            throw failure;
        }
    }

    private ProductCategory doDelete(Long id) {
        TenantContext context = context();
        ProductCategory category = find(id, context);
        long referenced = productRepository.countByCategory(context.tenantId(), context.storeId(), category.name());
        if (referenced > 0) {
            throw new BusinessException("PRODUCT_CATEGORY_IN_USE",
                    "分类「%s」下仍有 %d 个商品，请先改到其他分类再删除".formatted(category.name(), referenced));
        }
        categoryRepository.deleteById(id);
        return category;
    }

    /**
     * 分类写操作成功留痕：动作码与 {@code AuditActions} 登记的一致，幂等键取「动作 + 分类 ID」，
     * 同一分类的同一种变更重复上报只留一条。
     *
     * <p>detail 只放数值/枚举字段，运营填写的分类名走 {@code resourceName}
     * （由 {@link AuditClient#buildBody} 统一转义），避免手工拼 JSON 被引号/换行破坏成非法 detail 丢审计。
     */
    private void recordAudit(String action, ProductCategory category) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(category.tenantId())
                .storeId(category.storeId())
                .action(action)
                .resourceType(AUDIT_RESOURCE_TYPE)
                .resourceId(String.valueOf(category.id()))
                .resourceName(category.name())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .idempotencyKey(action + ":" + category.id())
                .detailJson("{\"storeId\":" + category.storeId() + ",\"sortOrder\":" + category.sortOrder()
                        + ",\"status\":\"" + category.status() + "\"}")
                .build());
    }

    /**
     * 分类写操作失败留痕：动作码与成功路径同码、{@code result=FAILURE} + 稳定错误码
     * （重名、仍被商品引用、分类不存在等规则拒绝同样要能回溯）。
     *
     * <p>审计只走 {@link AuditClient#recordAsync}（失败仅 WARN），业务异常原样抛出：留痕不改变业务结果。
     * 不带幂等键（重复失败各自留痕，也不覆盖成功路径的稳定键）。
     */
    private void recordFailure(String action, Long categoryId, RuntimeException failure) {
        TenantContext context = TenantContextHolder.get();
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(context == null ? null : context.tenantId())
                .storeId(context == null ? null : context.storeId())
                .action(action)
                .resourceType(AUDIT_RESOURCE_TYPE)
                .resourceId(categoryId == null ? null : String.valueOf(categoryId))
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"categoryId\":" + categoryId + "}")
                .build());
    }

    /** 改名同步：该门店下引用旧分类名的商品与点单目录项一起改成新名（点单页按目录项分类分组）。 */
    private void renameReferences(ProductCategory category, String newName) {
        productRepository.renameCategory(category.tenantId(), category.storeId(), category.name(), newName);
        catalogItemRepository.renameCategory(category.tenantId(), category.storeId(), category.name(), newName);
    }

    private ProductCategory find(Long id, TenantContext context) {
        ProductCategory category = categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException("PRODUCT_CATEGORY_NOT_FOUND", "商品分类不存在"));
        if (context.tenantId() != category.tenantId()
                || !Objects.equals(context.storeId(), category.storeId())) {
            throw new BusinessException("PRODUCT_CATEGORY_NOT_FOUND", "商品分类不存在");
        }
        return category;
    }

    private ProductCategory findByName(TenantContext context, String name) {
        return categoryRepository.findByName(context.tenantId(), context.storeId(), name).orElse(null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("PRODUCT_CATEGORY_INVALID", "分类名称不能为空");
        }
        String name = raw.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BusinessException("PRODUCT_CATEGORY_INVALID", "分类名称长度不能超过 %d 个字符".formatted(MAX_NAME_LENGTH));
        }
        return name;
    }

    private static String requireStatus(String raw) {
        String status = raw.trim().toUpperCase();
        if (!STATUS_ACTIVE.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new BusinessException("PRODUCT_CATEGORY_INVALID", "分类状态只能是 ACTIVE 或 DISABLED");
        }
        return status;
    }

    private static TenantContext context() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0 || context.storeId() == null) {
            throw new BusinessException("SAAS_CONTEXT_REQUIRED", "缺少有效门店上下文");
        }
        return context;
    }

    public record CategoryCommand(String name, Integer sortOrder, String status) {}
}
