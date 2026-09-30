package io.openware.platform.tenant.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.infra.persistence.mapper.PricingPlanMapper;
import io.openware.platform.tenant.infra.persistence.mapper.StoreMapper;
import io.openware.platform.tenant.infra.persistence.po.PricingPlanPo;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 计价方案内部端点；作用域为租户默认、业态默认、门店覆盖。 */
@RestController
@RequestMapping("/internal/pricing-plans")
public class InternalPricingPlanController {
    private final PricingPlanMapper pricingPlanMapper;
    private final StoreMapper storeMapper;

    public InternalPricingPlanController(PricingPlanMapper pricingPlanMapper, StoreMapper storeMapper) {
        this.pricingPlanMapper = pricingPlanMapper;
        this.storeMapper = storeMapper;
    }

    @GetMapping
    public List<PricingPlanView> list(@RequestParam(required = false) Long storeId,
                                   @RequestParam(required = false) String businessType) {
        long tenantId = requireTenant();
        LambdaQueryWrapper<PricingPlanPo> query = new LambdaQueryWrapper<PricingPlanPo>()
                .eq(PricingPlanPo::getTenantId, tenantId)
                .eq(PricingPlanPo::getStatus, "ACTIVE");
        if (storeId == null) {
            String normalized = normalize(businessType);
            if (normalized != null) query.eq(PricingPlanPo::getBusinessType, normalized);
            return pricingPlanMapper.selectList(query.orderByDesc(PricingPlanPo::getId)).stream()
                    .map(PricingPlanView::from).toList();
        }
        String actual = normalize(storeMapper.selectBusinessTypeById(storeId));
        if (actual == null) throw new ApiException(404, "STORE_NOT_FOUND", "门店不存在");
        String requested = normalize(businessType);
        if (requested != null && !requested.equals(actual)) {
            throw new ApiException(422, "BUSINESS_TYPE_MISMATCH", "业态与门店不匹配");
        }
        return effective(pricingPlanMapper.selectList(query), storeId, actual).stream()
                .map(PricingPlanView::from).toList();
    }

    @PostMapping
    public PricingPlanView create(@RequestBody CreatePricingRequest req) {
        if (req == null || req.resourceType() == null || req.resourceType().isBlank()) {
            throw new ApiException(400, "PRICING_PLAN_INVALID", "resourceType 不能为空");
        }
        long tenantId = requireTenant();
        if (req.tenantId() == null || tenantId != req.tenantId()) {
            throw new ApiException(403, "TENANT_SCOPE_FORBIDDEN", "租户作用域不匹配");
        }
        long storeId = req.storeId() == null ? 0L : req.storeId();
        String businessType = normalize(req.businessType());
        if (storeId > 0) {
            Long owner = storeMapper.selectTenantIdById(storeId);
            if (!Long.valueOf(tenantId).equals(owner)) {
                throw new ApiException(403, "STORE_SCOPE_FORBIDDEN", "门店不属于当前租户");
            }
            String actual = normalize(storeMapper.selectBusinessTypeById(storeId));
            if (actual == null) throw new ApiException(404, "STORE_NOT_FOUND", "门店不存在");
            if (businessType != null && !businessType.equals(actual)) {
                throw new ApiException(422, "BUSINESS_TYPE_MISMATCH", "业态与门店不匹配");
            }
            businessType = actual;
        }
        String scopeBusinessType = businessType == null ? "" : businessType;
        PricingPlanPo existing = pricingPlanMapper.selectOne(new LambdaQueryWrapper<PricingPlanPo>()
                .eq(PricingPlanPo::getTenantId, tenantId)
                .eq(PricingPlanPo::getStoreId, storeId)
                .eq(PricingPlanPo::getBusinessType, scopeBusinessType)
                .eq(PricingPlanPo::getResourceType, req.resourceType()).last("LIMIT 1"));
        if (req.idempotencyKey() != null && !req.idempotencyKey().isBlank()) {
            PricingPlanPo idem = pricingPlanMapper.selectOne(new LambdaQueryWrapper<PricingPlanPo>()
                    .eq(PricingPlanPo::getTenantId, tenantId)
                    .eq(PricingPlanPo::getIdempotencyKey, req.idempotencyKey())
                    .eq(PricingPlanPo::getResourceType, req.resourceType()).last("LIMIT 1"));
            if (idem != null) return PricingPlanView.from(idem);
        }
        if (existing != null && req.version() != null && !req.version().equals(existing.getVersion())) {
            throw new ApiException(409, "VERSION_CONFLICT", "计价方案已被其他请求修改");
        }
        PricingPlanPo po = existing == null ? new PricingPlanPo() : existing;
        po.setTenantId(tenantId);
        po.setStoreId(storeId);
        po.setBusinessType(scopeBusinessType);
        po.setResourceType(req.resourceType());
        po.setBillingUnit(req.billingUnit());
        po.setIncrementMinutes(req.incrementMinutes());
        po.setRoundingDirection(req.roundingDirection());
        po.setPricePerUnit(req.pricePerUnit());
        po.setDefaultSessionMinutes(req.defaultSessionMinutes());
        po.setOvertimeRate(req.overtimeRate());
        po.setStatus("ACTIVE");
        po.setIdempotencyKey(req.idempotencyKey());
        po.setUpdatedAt(LocalDateTime.now());
        if (existing == null) {
            po.setVersion(0);
            po.setCreatedAt(LocalDateTime.now());
            pricingPlanMapper.insert(po);
        } else {
            po.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
            pricingPlanMapper.updateById(po);
        }
        return PricingPlanView.from(po);
    }

    private long requireTenant() {
        var context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return context.tenantId();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static List<PricingPlanPo> effective(List<PricingPlanPo> rows, long storeId, String businessType) {
        List<PricingPlanPo> result = new ArrayList<>();
        for (String resourceType : List.of("KTV_ROOM", "KTV_SERVER")) {
            rows.stream().filter(row -> resourceType.equalsIgnoreCase(row.getResourceType()))
                    .filter(row -> (storeId == row.getStoreId() && businessType.equals(normalize(row.getBusinessType())))
                            || (row.getStoreId() == 0 && businessType.equals(normalize(row.getBusinessType())))
                            || (row.getStoreId() == 0 && (row.getBusinessType() == null || row.getBusinessType().isBlank())))
                    .sorted(Comparator.comparingInt(row -> storeId == row.getStoreId() ? 0
                            : (businessType.equals(normalize(row.getBusinessType())) ? 1 : 2)))
                    .findFirst().ifPresent(result::add);
        }
        return result;
    }

    public record CreatePricingRequest(Long tenantId, Long storeId, String businessType, String resourceType,
                                       String billingUnit, Integer incrementMinutes, String roundingDirection,
                                       Long pricePerUnit, Integer defaultSessionMinutes, BigDecimal overtimeRate,
                                       Integer version, String idempotencyKey) {}

    /** 跨服务契约 DTO；禁止把租户域 PO 直接暴露给 Admin/Order。 */
    public record PricingPlanView(Long id, Long tenantId, Long storeId, String businessType, String resourceType,
                                  String billingUnit, Integer incrementMinutes, String roundingDirection,
                                  Long pricePerUnit, Integer defaultSessionMinutes, BigDecimal overtimeRate,
                                  String status, Integer version, String idempotencyKey,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {
        static PricingPlanView from(PricingPlanPo po) {
            return new PricingPlanView(po.getId(), po.getTenantId(), po.getStoreId(), po.getBusinessType(),
                    po.getResourceType(), po.getBillingUnit(), po.getIncrementMinutes(), po.getRoundingDirection(),
                    po.getPricePerUnit(), po.getDefaultSessionMinutes(), po.getOvertimeRate(), po.getStatus(),
                    po.getVersion(), po.getIdempotencyKey(), po.getCreatedAt(), po.getUpdatedAt());
        }
    }
}
