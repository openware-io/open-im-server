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
import java.util.Objects;

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

    @PostMapping("/batch")
    @org.springframework.transaction.annotation.Transactional
    public BatchPricingView createBatch(@RequestBody BatchPricingRequest req) {
        if (req == null || req.plan() == null || req.storeIds() == null || req.storeIds().isEmpty()) {
            throw new ApiException(400, "PRICING_BATCH_INVALID", "storeIds 和 plan 不能为空");
        }
        long tenantId = requireTenant();
        CreatePricingRequest plan = req.plan();
        String requestedType = normalize(plan.businessType());
        String actualType = requestedType;
        for (Long storeId : req.storeIds()) {
            if (storeId == null || storeId <= 0 || !Objects.equals(storeMapper.selectTenantIdById(storeId), tenantId)) {
                throw new ApiException(403, "STORE_SCOPE_FORBIDDEN", "批量门店不属于当前租户");
            }
            String storeType = normalize(storeMapper.selectBusinessTypeById(storeId));
            if (storeType == null) throw new ApiException(404, "STORE_NOT_FOUND", "门店不存在");
            if (actualType == null) actualType = storeType;
            if (!actualType.equals(storeType)) throw new ApiException(422, "BUSINESS_TYPE_MISMATCH", "批量门店必须属于同一业态");
        }
        List<PricingPlanView> saved = new ArrayList<>();
        for (Long storeId : req.storeIds()) {
            CreatePricingRequest item = new CreatePricingRequest(tenantId, storeId, actualType, plan.resourceType(),
                    plan.billingUnit(), plan.incrementMinutes(), plan.roundingDirection(), plan.pricePerUnit(),
                    plan.defaultSessionMinutes(), plan.overtimeRate(), plan.version(), req.idempotencyKey());
            saved.add(create(item));
        }
        return new BatchPricingView(saved);
    }

    /** Admin/其他领域批量写入前的门店范围校验；只返回契约数据，不暴露门店 PO。 */
    @PostMapping("/store-scope/validate")
    public StoreScopeValidation validateStoreScope(@RequestBody StoreScopeRequest req) {
        long tenantId = requireTenant();
        if (req == null || req.storeIds() == null || req.storeIds().isEmpty()) {
            throw new ApiException(400, "STORE_SCOPE_INVALID", "storeIds 不能为空");
        }
        String businessType = normalize(req.businessType());
        List<StoreScopeItem> items = new ArrayList<>();
        for (Long storeId : req.storeIds()) {
            if (storeId == null || storeId <= 0 || !Objects.equals(storeMapper.selectTenantIdById(storeId), tenantId)) {
                throw new ApiException(403, "STORE_SCOPE_FORBIDDEN", "门店不属于当前租户");
            }
            String actual = normalize(storeMapper.selectBusinessTypeById(storeId));
            if (actual == null) throw new ApiException(404, "STORE_NOT_FOUND", "门店不存在");
            if (businessType != null && !businessType.equals(actual)) {
                throw new ApiException(422, "BUSINESS_TYPE_MISMATCH", "业态与门店不匹配");
            }
            if (businessType == null) businessType = actual;
            if (!businessType.equals(actual)) {
                throw new ApiException(422, "BUSINESS_TYPE_MISMATCH", "批量门店必须属于同一业态");
            }
            items.add(new StoreScopeItem(storeId, actual));
        }
        return new StoreScopeValidation(tenantId, businessType, items);
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

    public record BatchPricingRequest(CreatePricingRequest plan, List<Long> storeIds, String idempotencyKey) {}
    public record BatchPricingView(List<PricingPlanView> items) {}
    public record StoreScopeRequest(String businessType, List<Long> storeIds) {}
    public record StoreScopeValidation(Long tenantId, String businessType, List<StoreScopeItem> stores) {}
    public record StoreScopeItem(Long storeId, String businessType) {}

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
