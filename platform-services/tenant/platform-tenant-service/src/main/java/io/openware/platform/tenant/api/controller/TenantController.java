package io.openware.platform.tenant.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.tenant.infra.persistence.mapper.TenantMapper;
import io.openware.platform.tenant.infra.persistence.po.TenantPo;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/admin/platform/tenants")
public class TenantController {
    private final TenantMapper tenantMapper;
    private final AuditClient auditClient;

    public TenantController(TenantMapper tenantMapper, AuditClient auditClient) {
        this.tenantMapper = tenantMapper;
        this.auditClient = auditClient;
    }

    /**
     * 平台运营：租户列表（支付方式授权等平台管理页下拉用）。
     *
     * <p>{@code from}/{@code to} 按**租户创建时间** {@code created_at} 的闭区间筛选（统一口径见
     * {@link TimeRangeParams}：日期形态的 from/to 分别收口到当天起点与当天末尾；为空 = 不筛；
     * {@code from > to} → 400 {@code TIME_RANGE_INVALID}）。下拉调用不传即不筛，行为不变。
     */
    @GetMapping
    public java.util.List<TenantPo> list(@RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to) {
        TimeRange range = TimeRangeParams.parse(from, to);
        return tenantMapper.selectList(new LambdaQueryWrapper<TenantPo>()
                .ge(range.hasFrom(), TenantPo::getCreatedAt, range.fromInclusive())
                .le(range.hasTo(), TenantPo::getCreatedAt, range.toInclusive())
                .orderByDesc(TenantPo::getId));
    }

    /** 平台运营创建租户（ADM-02）。幂等：tenantCode 已存在则返回既有租户，不重复落库。 */
    @PostMapping
    public TenantPo create(@RequestBody CreateTenantRequest req) {
        try {
            TenantPo existing = tenantMapper.selectOne(
                new LambdaQueryWrapper<TenantPo>().eq(TenantPo::getTenantCode, req.tenantCode()));
            if (existing != null) {
                return existing;
            }
            TenantPo po = new TenantPo();
            po.setTenantCode(req.tenantCode());
            po.setName(req.name());
            po.setStatus("PENDING");
            po.setDefaultLocale(req.defaultLocale());
            po.setDefaultTimezone(req.defaultTimezone());
            po.setCreatedAt(LocalDateTime.now());
            po.setUpdatedAt(LocalDateTime.now());
            tenantMapper.insert(po);
            // 平台级动作：tenant_id 记为新租户（便于按租户检索），operator_type 强制 PLATFORM。
            // 操作人由网关注入的平台作用域签名上下文补全（tenantId=0 + scopeType=PLATFORM + subject=平台账号）：
            // /api/v1/admin/platform/** 在网关属 PLATFORM_CONTEXT 路由，运营未选择租户上下文时下发平台 token。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(po.getId())
                    .action("tenant.create")
                    .operatorType(AuditClient.AuditRecord.OPERATOR_TYPE_PLATFORM)
                    .resourceType("tnt_tenant").resourceId(String.valueOf(po.getId()))
                    .resourceName(req.name())
                    .idempotencyKey("tenant-create:" + req.tenantCode())
                    .detailJson("{\"tenantCode\":\"" + req.tenantCode() + "\",\"name\":\"" + req.name()
                            + "\",\"status\":\"PENDING\"}")
                    .build());
            return po;
        } catch (RuntimeException failure) {
            // 失败留痕（编号冲突/参数非法/落库失败）：审计只 WARN，业务异常原样抛出。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action("tenant.create")
                    .operatorType(AuditClient.AuditRecord.OPERATOR_TYPE_PLATFORM)
                    .resourceType("tnt_tenant")
                    .resourceId(req == null ? null : req.tenantCode())
                    .result(AuditClient.AuditRecord.RESULT_FAILURE)
                    .errorCode(AuditErrorCodes.of(failure))
                    .detailJson("{\"tenantCode\":\"" + (req == null ? "" : req.tenantCode()) + "\"}")
                    .build());
            throw failure;
        }
    }

    public record CreateTenantRequest(String tenantCode, String name, String defaultLocale, String defaultTimezone) {}
}
