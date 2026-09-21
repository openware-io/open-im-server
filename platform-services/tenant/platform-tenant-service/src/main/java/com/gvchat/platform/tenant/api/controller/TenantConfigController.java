package com.gvchat.platform.tenant.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.audit.AuditErrorCodes;
import com.gvchat.infrastructure.tenant.PermissionGuard;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.tenant.application.WalletTokenConfigApplicationService;
import com.gvchat.platform.tenant.application.WalletTokenConfigApplicationService.TenantWalletTokenConfig;
import com.gvchat.platform.tenant.infra.persistence.mapper.TenantConfigMapper;
import com.gvchat.platform.tenant.infra.persistence.po.TenantConfigPo;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 租户级钱包代币（储值品牌）配置：代币展示名 + 主单位与代币的比例（默认 1:100）。
 *
 * <p><b>ratio 语义（冻结，见 16_CURRENCY_CONVENTIONS §9）</b>：{@code ratio = N} 表示
 * <b>1 个主单位（1 元 / 1 美元，随租户币种，默认 USD）= N 代币</b>。
 * 该比例<b>仅用于展示与文案</b>（例如界面提示「充 100 元得 10,000 A380币」），
 * <b>硬约束：禁止参与任何入账 / 扣减 / 对账 / 退款 / 日结计算</b>——钱包账本、支付、退款、班次日结
 * 一律只用<b>最小货币单位</b>（CNY 分 / USD cent）的整数金额，不按 ratio 折算；跨币种不做折算（§1）。
 * 本接口只做键值读写，不引入任何换算逻辑。
 *
 * <p>读取：租户一律取自签名上下文（不信任请求体/查询参数），仅能读自己租户的配置，
 * 这样账单/支付/充值各端（含 C 端 H5）都能拿到展示名而不泄露其他租户配置。
 * <p>写入：仍要求经营权限 {@code tenant.tenant.manage} 且同租户。
 */
@RestController
@RequestMapping("/admin/tenant/config")
public class TenantConfigController {

    private final TenantConfigMapper tenantConfigMapper;
    private final AuditClient auditClient;
    private final WalletTokenConfigApplicationService walletTokenConfigService;

    public TenantConfigController(TenantConfigMapper tenantConfigMapper, AuditClient auditClient,
                                  WalletTokenConfigApplicationService walletTokenConfigService) {
        this.tenantConfigMapper = tenantConfigMapper;
        this.auditClient = auditClient;
        this.walletTokenConfigService = walletTokenConfigService;
    }

    /**
     * 当前租户的钱包代币展示名与比例（租户来自上下文）。
     *
     * <p>{@code ratio} 是展示口径：1 个主单位（随租户币种）= ratio 个代币；调用方<b>不得</b>用它做金额换算。
     * 缺行/空值/非法值一律回退 {@code A380币 / 100}（与内部端点同源，读路径不抛错）。
     */
    @GetMapping
    public WalletTokenConfig get(@RequestParam(required = false) Long tenantId) {
        Long scopedTenantId = requireScopedTenant(tenantId);
        TenantWalletTokenConfig config = walletTokenConfigService.resolve(scopedTenantId);
        return new WalletTokenConfig(config.brandName(), config.ratio());
    }

    /** 修改展示名与比例（经营权限 + 同租户）。 */
    @PutMapping
    @Transactional
    public WalletTokenConfig update(@RequestBody UpdateRequest req) {
        try {
            PermissionGuard.require("tenant.tenant.manage");
            Long scopedTenantId = requireScopedTenant(tenantIdOf(req));
            upsert(scopedTenantId, WalletTokenConfigApplicationService.CONFIG_KEY_BRAND_NAME, req.brandName());
            upsert(scopedTenantId, WalletTokenConfigApplicationService.CONFIG_KEY_RATIO, String.valueOf(req.ratio()));
            // 租户级配置改动影响全租户账单/充值展示：留痕并记录变更前后值摘要。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(scopedTenantId)
                    .action("tenant.config.update")
                    .resourceType("tnt_tenant_config").resourceId(String.valueOf(scopedTenantId))
                    .resourceName(req.brandName())
                    .detailJson("{\"brandName\":\"" + req.brandName() + "\",\"ratio\":" + req.ratio() + "}")
                    .build());
            return new WalletTokenConfig(req.brandName(), req.ratio());
        } catch (RuntimeException failure) {
            // 失败出口留痕（缺权限/越权租户/参数非法/落库失败）：审计只 WARN，业务异常原样抛出。
            recordFailure("tenant.config.update", failure, tenantIdOf(req));
            throw failure;
        }
    }

    /**
     * 领域内失败留痕：租户级配置写操作的失败出口此前完全没有留痕（BFF 拦截器只覆盖 BFF 路径）。
     * detail 只放可检索的租户 ID，配置内容（品牌名）不重复进失败详情。
     */
    private void recordFailure(String action, RuntimeException failure, Long requestedTenantId) {
        TenantContext context = TenantContextHolder.get();
        Long tenantId = requestedTenantId != null ? requestedTenantId
                : (context == null ? null : context.tenantId());
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(tenantId)
                .action(action)
                .resourceType("tnt_tenant_config")
                .resourceId(tenantId == null ? null : String.valueOf(tenantId))
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .build());
    }

    private static Long tenantIdOf(UpdateRequest req) {
        return req == null ? null : req.tenantId();
    }

    /**
     * 租户边界：请求里的 tenantId 只能与上下文一致（缺省时直接用上下文租户）。
     * 原实现直接按查询参数读任意租户，任何会话都能跨租户读取。
     */
    private Long requireScopedTenant(Long requestedTenantId) {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        if (requestedTenantId != null && !requestedTenantId.equals(context.tenantId())) {
            throw new ApiException(403, "TENANT_SCOPE_DENIED", "只能访问当前上下文的租户配置");
        }
        return context.tenantId();
    }

    private void upsert(Long tenantId, String key, String value) {
        List<TenantConfigPo> rows = tenantConfigMapper.selectList(new QueryWrapper<TenantConfigPo>()
                .eq("tenant_id", tenantId).eq("store_id", 0L).eq("config_key", key));
        if (!rows.isEmpty()) {
            TenantConfigPo po = rows.get(0);
            po.setConfigValue(value);
            po.setUpdatedAt(LocalDateTime.now());
            tenantConfigMapper.updateById(po);
            return;
        }
        TenantConfigPo po = new TenantConfigPo();
        po.setTenantId(tenantId);
        po.setStoreId(0L);
        po.setConfigKey(key);
        po.setConfigValue(value);
        po.setStatus("ACTIVE");
        po.setVersion(0);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        tenantConfigMapper.insert(po);
    }

    public record WalletTokenConfig(String brandName, long ratio) {}
    public record UpdateRequest(Long tenantId, String brandName, long ratio) {}
}
