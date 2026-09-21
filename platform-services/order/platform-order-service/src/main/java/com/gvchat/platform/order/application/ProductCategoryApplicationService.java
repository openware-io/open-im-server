package com.gvchat.platform.order.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.audit.AuditErrorCodes;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.infra.persistence.mapper.CatalogItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.ProductCategoryMapper;
import com.gvchat.platform.order.infra.persistence.mapper.ProductMapper;
import com.gvchat.platform.order.infra.persistence.po.CatalogItemPo;
import com.gvchat.platform.order.infra.persistence.po.ProductCategoryPo;
import com.gvchat.platform.order.infra.persistence.po.ProductPo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品分类字典（按门店维护）。
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
    public static final int MAX_NAME_LENGTH = 64;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";

    /** 审计资源类型与动作码：与 {@code AuditActions} 的稳定码一致（前端按码筛选）。 */
    private static final String AUDIT_RESOURCE_TYPE = "ord_product_category";
    private static final String ACTION_CREATE = "product.category.create";
    private static final String ACTION_UPDATE = "product.category.update";
    private static final String ACTION_DELETE = "product.category.delete";

    private final ProductCategoryMapper categoryMapper;
    private final ProductMapper productMapper;
    private final CatalogItemMapper catalogItemMapper;
    private final AuditClient auditClient;

    @Autowired
    public ProductCategoryApplicationService(ProductCategoryMapper categoryMapper, ProductMapper productMapper,
                                             CatalogItemMapper catalogItemMapper, AuditClient auditClient) {
        this.categoryMapper = categoryMapper;
        this.productMapper = productMapper;
        this.catalogItemMapper = catalogItemMapper;
        this.auditClient = auditClient;
    }

    /** 兼容既有单测装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
    public ProductCategoryApplicationService(ProductCategoryMapper categoryMapper, ProductMapper productMapper,
                                             CatalogItemMapper catalogItemMapper) {
        this(categoryMapper, productMapper, catalogItemMapper, AuditClient.disabled());
    }

    /** 分类列表：按 sort_order、name 排序；status 传入时只看该状态（如 status=ACTIVE 只看启用）。 */
    public List<ProductCategoryPo> list(Long storeId, String status) {
        TenantContext context = context();
        if (storeId != null && !storeId.equals(context.storeId())) {
            throw new BusinessException("STORE_SCOPE_DENIED", "无权访问该门店分类");
        }
        return categoryMapper.selectList(new LambdaQueryWrapper<ProductCategoryPo>()
                .eq(ProductCategoryPo::getTenantId, context.tenantId())
                .eq(ProductCategoryPo::getStoreId, context.storeId())
                .eq(status != null && !status.isBlank(), ProductCategoryPo::getStatus, status)
                .orderByAsc(ProductCategoryPo::getSortOrder)
                .orderByAsc(ProductCategoryPo::getName)
                .orderByAsc(ProductCategoryPo::getId));
    }

    @Transactional
    public ProductCategoryPo create(CategoryCommand command) {
        try {
            ProductCategoryPo po = doCreate(command);
            recordAudit(ACTION_CREATE, po);
            return po;
        } catch (RuntimeException failure) {
            recordFailure(ACTION_CREATE, null, failure);
            throw failure;
        }
    }

    private ProductCategoryPo doCreate(CategoryCommand command) {
        TenantContext context = context();
        String name = requireName(command.name());
        if (findByName(context, name) != null) {
            throw new BusinessException("PRODUCT_CATEGORY_DUPLICATED", "分类名称已存在：" + name);
        }
        ProductCategoryPo po = new ProductCategoryPo();
        po.setTenantId(context.tenantId());
        po.setStoreId(context.storeId());
        po.setName(name);
        po.setSortOrder(command.sortOrder() == null ? 0 : command.sortOrder());
        po.setStatus(STATUS_ACTIVE);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        categoryMapper.insert(po);
        return po;
    }

    @Transactional
    public ProductCategoryPo update(Long id, CategoryCommand command) {
        try {
            ProductCategoryPo po = doUpdate(id, command);
            recordAudit(ACTION_UPDATE, po);
            return po;
        } catch (RuntimeException failure) {
            recordFailure(ACTION_UPDATE, id, failure);
            throw failure;
        }
    }

    private ProductCategoryPo doUpdate(Long id, CategoryCommand command) {
        TenantContext context = context();
        ProductCategoryPo po = find(id, context);
        if (command.name() != null && !command.name().isBlank()) {
            String name = requireName(command.name());
            if (!name.equals(po.getName())) {
                ProductCategoryPo duplicated = findByName(context, name);
                if (duplicated != null && !duplicated.getId().equals(po.getId())) {
                    throw new BusinessException("PRODUCT_CATEGORY_DUPLICATED", "分类名称已存在：" + name);
                }
                renameReferences(po, name);
                po.setName(name);
            }
        }
        if (command.sortOrder() != null) {
            po.setSortOrder(command.sortOrder());
        }
        if (command.status() != null && !command.status().isBlank()) {
            po.setStatus(requireStatus(command.status()));
        }
        po.setUpdatedAt(LocalDateTime.now());
        categoryMapper.updateById(po);
        return po;
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

    private ProductCategoryPo doDelete(Long id) {
        TenantContext context = context();
        ProductCategoryPo po = find(id, context);
        Long referenced = productMapper.selectCount(new LambdaQueryWrapper<ProductPo>()
                .eq(ProductPo::getTenantId, context.tenantId())
                .eq(ProductPo::getStoreId, context.storeId())
                .eq(ProductPo::getCategory, po.getName()));
        if (referenced != null && referenced > 0) {
            throw new BusinessException("PRODUCT_CATEGORY_IN_USE",
                    "分类「%s」下仍有 %d 个商品，请先改到其他分类再删除".formatted(po.getName(), referenced));
        }
        categoryMapper.deleteById(id);
        return po;
    }

    /**
     * 分类写操作成功留痕：动作码与 {@code AuditActions} 登记的一致，幂等键取「动作 + 分类 ID」，
     * 同一分类的同一种变更重复上报只留一条。
     *
     * <p>detail 只放数值/枚举字段，运营填写的分类名走 {@code resourceName}
     * （由 {@link AuditClient#buildBody} 统一转义），避免手工拼 JSON 被引号/换行破坏成非法 detail 丢审计。
     */
    private void recordAudit(String action, ProductCategoryPo po) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(po.getTenantId())
                .storeId(po.getStoreId())
                .action(action)
                .resourceType(AUDIT_RESOURCE_TYPE)
                .resourceId(String.valueOf(po.getId()))
                .resourceName(po.getName())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .idempotencyKey(action + ":" + po.getId())
                .detailJson("{\"storeId\":" + po.getStoreId() + ",\"sortOrder\":" + po.getSortOrder()
                        + ",\"status\":\"" + po.getStatus() + "\"}")
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
    private void renameReferences(ProductCategoryPo category, String newName) {
        LocalDateTime now = LocalDateTime.now();
        productMapper.update(null, new LambdaUpdateWrapper<ProductPo>()
                .eq(ProductPo::getTenantId, category.getTenantId())
                .eq(ProductPo::getStoreId, category.getStoreId())
                .eq(ProductPo::getCategory, category.getName())
                .set(ProductPo::getCategory, newName)
                .set(ProductPo::getUpdatedAt, now));
        catalogItemMapper.update(null, new LambdaUpdateWrapper<CatalogItemPo>()
                .eq(CatalogItemPo::getTenantId, category.getTenantId())
                .eq(CatalogItemPo::getStoreId, category.getStoreId())
                .eq(CatalogItemPo::getCategory, category.getName())
                .set(CatalogItemPo::getCategory, newName)
                .set(CatalogItemPo::getUpdatedAt, now));
    }

    private ProductCategoryPo find(Long id, TenantContext context) {
        ProductCategoryPo po = categoryMapper.selectOne(new LambdaQueryWrapper<ProductCategoryPo>()
                .eq(ProductCategoryPo::getTenantId, context.tenantId())
                .eq(ProductCategoryPo::getStoreId, context.storeId())
                .eq(ProductCategoryPo::getId, id));
        if (po == null) {
            throw new BusinessException("PRODUCT_CATEGORY_NOT_FOUND", "商品分类不存在");
        }
        return po;
    }

    private ProductCategoryPo findByName(TenantContext context, String name) {
        return categoryMapper.selectOne(new LambdaQueryWrapper<ProductCategoryPo>()
                .eq(ProductCategoryPo::getTenantId, context.tenantId())
                .eq(ProductCategoryPo::getStoreId, context.storeId())
                .eq(ProductCategoryPo::getName, name));
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
