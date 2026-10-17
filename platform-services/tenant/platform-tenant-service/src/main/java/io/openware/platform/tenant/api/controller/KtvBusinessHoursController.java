package io.openware.platform.tenant.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.application.KtvBusinessHoursApplicationService;
import io.openware.platform.tenant.application.KtvBusinessHoursApplicationService.BusinessHoursView;
import org.springframework.web.bind.annotation.*;

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
 * <p>读取：租户一律取自签名上下文（不接受跨租户读）；写入：权限、校验与审计在
 * {@link KtvBusinessHoursApplicationService#updateWithAudit}，本类只做协议转换与上下文提取。
 */
@RestController
@RequestMapping("/admin/tenant/business-hours")
public class KtvBusinessHoursController {

    private final KtvBusinessHoursApplicationService businessHoursService;

    public KtvBusinessHoursController(KtvBusinessHoursApplicationService businessHoursService) {
        this.businessHoursService = businessHoursService;
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
    public BusinessHoursView update(@RequestBody UpdateRequest req) {
        Long tenantId = requireTenant();
        return businessHoursService.updateWithAudit(tenantId,
                req == null ? null : req.storeId(),
                req == null ? null : req.storeIds(),
                req == null ? null : req.businessType(),
                req == null ? null : req.openTime(),
                req == null ? null : req.closeTime());
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
    }
}
