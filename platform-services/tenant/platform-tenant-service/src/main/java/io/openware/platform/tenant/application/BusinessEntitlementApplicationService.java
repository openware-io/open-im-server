package io.openware.platform.tenant.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.infra.persistence.mapper.BusinessEntitlementMapper;
import io.openware.platform.tenant.infra.persistence.po.BusinessEntitlementPo;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户业态开通：总部业务组合和门店工作台的可见性来源。
 * 门店的业务类型仍由 tnt_store 决定；本服务只回答“租户是否开通该业态”。
 */
@Service
public class BusinessEntitlementApplicationService {

    private static final String PERMISSION_VIEW = "tenant.overview.view";
    private static final String PERMISSION_MANAGE = "tenant.business.manage";

    private final BusinessEntitlementMapper mapper;

    public BusinessEntitlementApplicationService(BusinessEntitlementMapper mapper) {
        this.mapper = mapper;
    }

    public List<BusinessEntitlementView> list() {
        TenantContext context = requireContext(PERMISSION_VIEW);
        return mapper.selectList(new LambdaQueryWrapper<BusinessEntitlementPo>()
                .eq(BusinessEntitlementPo::getTenantId, context.tenantId())
                .eq(BusinessEntitlementPo::getStatus, "ACTIVE")
                .orderByAsc(BusinessEntitlementPo::getBusinessType)).stream()
                .map(BusinessEntitlementApplicationService::toView).toList();
    }

    @Transactional
    public BusinessEntitlementView enable(String businessType) {
        TenantContext context = requireContext(PERMISSION_MANAGE);
        String type = businessType == null ? "" : businessType.trim().toUpperCase();
        if (type.isBlank()) {
            throw new ApiException(400, "BUSINESS_TYPE_REQUIRED", "业态编码不能为空");
        }
        BusinessEntitlementPo existing = mapper.selectOne(new LambdaQueryWrapper<BusinessEntitlementPo>()
                .eq(BusinessEntitlementPo::getTenantId, context.tenantId())
                .eq(BusinessEntitlementPo::getBusinessType, type)
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            BusinessEntitlementPo created = new BusinessEntitlementPo();
            created.setTenantId(context.tenantId());
            created.setBusinessType(type);
            created.setStatus("ACTIVE");
            created.setSource("MANUAL");
            created.setEffectiveFrom(now);
            created.setCreatedBy(context.accountId());
            created.setCreatedAt(now);
            created.setUpdatedBy(context.accountId());
            created.setUpdatedAt(now);
            mapper.insert(created);
            return toView(created);
        }
        existing.setStatus("ACTIVE");
        existing.setSource("MANUAL");
        existing.setEffectiveFrom(now);
        existing.setUpdatedBy(context.accountId());
        existing.setUpdatedAt(now);
        mapper.updateById(existing);
        return toView(existing);
    }

    private static BusinessEntitlementView toView(BusinessEntitlementPo po) {
        return new BusinessEntitlementView(po.getId(), po.getBusinessType(), po.getStatus(), po.getSource());
    }

    public record BusinessEntitlementView(Long id, String businessType, String status, String source) {
    }

    private TenantContext requireContext(String permission) {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(403, "TENANT_SCOPE_REQUIRED", "必须在租户上下文中查看业态开通");
        }
        if (context.permissions() == null || !context.permissions().contains(permission)) {
            throw new ApiException(403, "PERMISSION_DENIED", "缺少权限: " + permission);
        }
        return context;
    }
}
