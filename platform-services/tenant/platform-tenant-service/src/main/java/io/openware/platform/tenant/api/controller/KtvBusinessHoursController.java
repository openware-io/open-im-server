package io.openware.platform.tenant.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.application.KtvBusinessHoursApplicationService;
import io.openware.platform.tenant.application.KtvBusinessHoursApplicationService.BusinessHoursView;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * KTV 营业时间配置（门店级覆盖租户默认）。
 *
 * <p><b>为什么是「门店级 + 租户默认」</b>：KTV 是夜间业态，默认 <b>18:00 – 次日 05:00</b>；
 * 同一租户下不同门店的营业时间可能不同（例如午市门店），因此按门店可覆盖，未配置的门店继承租户默认。
 *
 * <p><b>统一原则</b>：预约「到店时间」必须落在营业时段内，这条规则由服务端
 * （platform-order-service 的预约创建）统一校验；C 端 / B 端 / 后台代客预约都走同一个创建接口，
 * 客户端只用本接口读到的时间去**约束选择器**（例如只能选 18:00 之后的时段），不再各自写死。
 *
 * <p>读取：租户一律取自签名上下文（不接受跨租户读）；写入：要求经营权限
 * {@code tenant.tenant.manage} 并留痕 {@code tenant.business_hours.update}。
 */
@RestController
@RequestMapping("/admin/tenant/business-hours")
public class KtvBusinessHoursController {

    private final KtvBusinessHoursApplicationService businessHoursService;
    private final AuditClient auditClient;

    public KtvBusinessHoursController(KtvBusinessHoursApplicationService businessHoursService,
                                      io.openware.platform.tenant.infra.persistence.mapper.TenantConfigMapper ignored,
                                      AuditClient auditClient) {
        this(businessHoursService, auditClient);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public KtvBusinessHoursController(KtvBusinessHoursApplicationService businessHoursService,
                                      AuditClient auditClient) {
        this.businessHoursService = businessHoursService;
        this.auditClient = auditClient;
    }

    /**
     * 生效营业时间（门店行 → 租户默认行 → 缺省 18:00–05:00）。
     *
     * @param storeId 门店；缺省用上下文门店，传 0 表示「只看租户默认」
     */
    @GetMapping
    public BusinessHoursView get(@RequestParam(required = false) Long storeId) {
        Long tenantId = requireTenant();
        Long scopedStoreId = storeId != null ? storeId : scopedStoreId();
        return BusinessHoursView.of(scopedStoreId, businessHoursService.resolve(tenantId, scopedStoreId, null));
    }

    /**
     * 保存营业时间：{@code storeId} 为 0/null 时写**租户默认**，否则写该门店的覆盖值。
     *
     * <p>时间格式 {@code HH:mm}（或 {@code HH:mm:ss}）；{@code openTime == closeTime} 视为全天营业。
     * 跨自然日（如 18:00–05:00）是允许且必需的：营业时间不是「当天内的区间」。
     */
    @PutMapping
    @Transactional
    public BusinessHoursView update(@RequestBody UpdateRequest req) {
        Long tenantId = requireTenant();
        try {
            PermissionGuard.require("tenant.tenant.manage");
            if (req == null || req.openTime() == null || req.closeTime() == null) {
                throw new ApiException(400, "BUSINESS_HOURS_REQUIRED", "缺少营业时间（openTime/closeTime）");
            }
            LocalTime open = parseTime(req.openTime(), "openTime");
            LocalTime close = parseTime(req.closeTime(), "closeTime");
            List<Long> targets = new ArrayList<>(new LinkedHashSet<>(req.targetStoreIds()));
            String requestedBusinessType = req.businessType();
            if (targets.isEmpty()) targets.add(0L);
            if (targets.contains(0L) && targets.size() > 1) {
                throw new ApiException(400, "STORE_TARGET_MIXED", "租户默认与门店覆盖不能在同一批次写入");
            }
            if (targets.size() == 1 && targets.get(0) == 0L && requestedBusinessType == null) {
                requestedBusinessType = null;
            }
            String value = KtvBusinessHoursApplicationService.format(open, close);
            businessHoursService.update(tenantId, targets.stream().filter(id -> id > 0).toList(), requestedBusinessType, open, close);
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(tenantId)
                    .action("tenant.business_hours.update")
                    .resourceType("tnt_tenant_config")
                    .resourceId(String.valueOf(tenantId))
                    .resourceName(targets.size() == 1 && targets.get(0) == 0L ? "租户/业态默认营业时间" : "批量门店营业时间")
                    .detailJson("{\"storeIds\":" + targets + ",\"businessType\":\"" + (requestedBusinessType == null ? "" : requestedBusinessType) + "\",\"businessHours\":\"" + value + "\"}")
                    .build());
            Long viewStoreId = targets.size() == 1 ? targets.get(0) : 0L;
            return BusinessHoursView.of(viewStoreId, businessHoursService.resolve(tenantId, viewStoreId, requestedBusinessType));
        } catch (RuntimeException failure) {
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(tenantId)
                    .action("tenant.business_hours.update")
                    .resourceType("tnt_tenant_config")
                    .resourceId(String.valueOf(tenantId))
                    .result(AuditClient.AuditRecord.RESULT_FAILURE)
                    .errorCode(AuditErrorCodes.of(failure))
                    .build());
            throw failure;
        }
    }

    /** 解析 {@code HH:mm} / {@code HH:mm:ss}；格式非法一律 400（写路径不静默改写用户的输入）。 */
    private static LocalTime parseTime(String raw, String field) {
        try {
            return LocalTime.parse(raw.trim());
        } catch (DateTimeParseException invalid) {
            throw new ApiException(400, "TIME_FORMAT_INVALID",
                    field + " 时间格式应为 HH:mm（例如 18:00）");
        }
    }

    private Long requireTenant() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return context.tenantId();
    }

    private Long scopedStoreId() {
        TenantContext context = TenantContextHolder.get();
        return context == null || context.storeId() == null ? 0L : context.storeId();
    }

    public record UpdateRequest(Long storeId, List<Long> storeIds, String businessType,
                                String openTime, String closeTime) {
        List<Long> targetStoreIds() {
            List<Long> result = new ArrayList<>();
            if (storeIds != null) result.addAll(storeIds);
            if (storeId != null) result.add(storeId);
            return result;
        }
    }
}
