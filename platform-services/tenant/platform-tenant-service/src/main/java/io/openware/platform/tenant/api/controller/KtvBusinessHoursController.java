package io.openware.platform.tenant.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.application.KtvBusinessHoursApplicationService;
import io.openware.platform.tenant.application.KtvBusinessHoursApplicationService.BusinessHoursView;
import io.openware.platform.tenant.infra.persistence.mapper.TenantConfigMapper;
import io.openware.platform.tenant.infra.persistence.po.TenantConfigPo;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
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
    private final TenantConfigMapper tenantConfigMapper;
    private final AuditClient auditClient;

    public KtvBusinessHoursController(KtvBusinessHoursApplicationService businessHoursService,
                                      TenantConfigMapper tenantConfigMapper, AuditClient auditClient) {
        this.businessHoursService = businessHoursService;
        this.tenantConfigMapper = tenantConfigMapper;
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
        return BusinessHoursView.of(scopedStoreId, businessHoursService.resolve(tenantId, scopedStoreId));
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
            Long targetStoreId = req.storeId() == null ? 0L : req.storeId();
            if (targetStoreId < 0) {
                throw new ApiException(400, "STORE_ID_INVALID", "storeId 不能为负数");
            }
            String value = KtvBusinessHoursApplicationService.format(open, close);
            upsert(tenantId, targetStoreId, value);
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(tenantId)
                    .action("tenant.business_hours.update")
                    .resourceType("tnt_tenant_config")
                    .resourceId(String.valueOf(tenantId))
                    .resourceName(targetStoreId == 0L ? "租户默认营业时间" : "门店 " + targetStoreId + " 营业时间")
                    .detailJson("{\"storeId\":" + targetStoreId + ",\"businessHours\":\"" + value + "\"}")
                    .build());
            return BusinessHoursView.of(targetStoreId, businessHoursService.resolve(tenantId, targetStoreId));
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

    /** 配置落库：{@code (tenant_id, store_id, config_key)} 唯一，存在即更新，不存在则插入。 */
    private void upsert(Long tenantId, Long storeId, String value) {
        List<TenantConfigPo> rows = tenantConfigMapper.selectList(new QueryWrapper<TenantConfigPo>()
                .eq("tenant_id", tenantId).eq("store_id", storeId)
                .eq("config_key", KtvBusinessHoursApplicationService.CONFIG_KEY));
        if (!rows.isEmpty()) {
            TenantConfigPo po = rows.get(0);
            po.setConfigValue(value);
            po.setUpdatedAt(LocalDateTime.now());
            tenantConfigMapper.updateById(po);
            return;
        }
        TenantConfigPo po = new TenantConfigPo();
        po.setTenantId(tenantId);
        po.setStoreId(storeId);
        po.setConfigKey(KtvBusinessHoursApplicationService.CONFIG_KEY);
        po.setConfigValue(value);
        po.setStatus("ACTIVE");
        po.setVersion(0);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        tenantConfigMapper.insert(po);
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

    public record UpdateRequest(Long storeId, String openTime, String closeTime) {}
}
