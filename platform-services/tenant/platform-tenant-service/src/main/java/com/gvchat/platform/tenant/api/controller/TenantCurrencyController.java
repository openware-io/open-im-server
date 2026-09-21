package com.gvchat.platform.tenant.api.controller;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.audit.AuditErrorCodes;
import com.gvchat.infrastructure.currency.Currency;
import com.gvchat.infrastructure.tenant.PermissionGuard;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.tenant.application.TenantCurrencyApplicationService;
import com.gvchat.platform.tenant.application.TenantCurrencyApplicationService.CurrencySwitchResult;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * 租户级币种配置（`docs/standards/16_CURRENCY_CONVENTIONS.md` §2）。
 *
 * <p>读写契约：
 * <ul>
 *   <li>{@code GET  /admin/tenant/currency} → {@code {currencyCode, symbol, minorUnitDigits, supported}}</li>
 *   <li>{@code PUT  /admin/tenant/currency} body {@code {currencyCode, migrateBalances?}} → 同 GET 结构</li>
 * </ul>
 *
 * <p>**租户边界**：一律取签名上下文租户，与 {@link TenantConfigController} 同款边界写法 —— 请求里若出现
 * 与上下文不一致的 {@code tenantId}，直接 403，绝不作为选租户的依据。平台运营要改别的租户 = 先切到目标租户上下文。
 *
 * <p>读只要求租户上下文（C 端也要读本租户币种）；写要求权限 {@code tenant.currency.manage}，
 * 并且必须处理 §2.2 的联动（门店写穿、钱包余额保护），联动与落库在同一事务内，审计含前后值与受影响行数。
 */
@RestController
@RequestMapping("/admin/tenant/currency")
public class TenantCurrencyController {

    private final TenantCurrencyApplicationService currencyService;
    private final AuditClient auditClient;

    public TenantCurrencyController(TenantCurrencyApplicationService currencyService, AuditClient auditClient) {
        this.currencyService = currencyService;
        this.auditClient = auditClient;
    }

    /** 当前租户币种（租户来自签名上下文）。 */
    @GetMapping
    public CurrencyView get(@RequestParam(value = "tenantId", required = false) Long tenantId) {
        long scopedTenantId = requireScopedTenant(tenantId);
        return view(currencyService.resolve(scopedTenantId));
    }

    /** 切换当前租户币种（权限 tenant.currency.manage + 同租户边界 + 联动同事务）。 */
    @PutMapping
    public CurrencyView update(@RequestParam(value = "tenantId", required = false) Long tenantId,
                               @RequestBody(required = false) UpdateCurrencyRequest request) {
        try {
            // 先判「有没有身份」（无上下文 401），再判「有没有权限」（缺权限 403），避免无身份被误报成越权。
            long scopedTenantId = requireScopedTenant(tenantId);
            PermissionGuard.require("tenant.currency.manage");
            Currency target = TenantCurrencyApplicationService.requireSupported(
                    request == null ? null : request.currencyCode());
            boolean migrateBalances = request != null && Boolean.TRUE.equals(request.migrateBalances());

            CurrencySwitchResult result = currencyService.switchCurrency(scopedTenantId, target, migrateBalances);

            // 币种是租户级唯一来源，且本次可能改写门店与钱包账户：必须留痕「变更前/后 + 受影响行数」。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(scopedTenantId)
                    .action("tenant.currency.update")
                    .resourceType("tnt_tenant_config").resourceId(String.valueOf(scopedTenantId))
                    .resourceName(target.code())
                    .detailJson(detailJson(result))
                    .build());

            return view(result.after());
        } catch (RuntimeException failure) {
            // 失败出口留痕（缺权限/越权租户/币种非法/余额阻断/落库失败）：审计只 WARN，异常原样抛出。
            recordFailure(tenantId, request, failure);
            throw failure;
        }
    }

    /**
     * 领域内失败留痕：币种切换失败（尤其 §2.2.2 的余额阻断）必须可回溯，否则「为什么改不了币种」
     * 只能看应用日志。detail 只记请求币种（受控枚举码），余额金额不入审计详情。
     */
    private void recordFailure(Long requestedTenantId, UpdateCurrencyRequest request, RuntimeException failure) {
        TenantContext context = TenantContextHolder.get();
        Long tenantId = requestedTenantId != null ? requestedTenantId
                : (context == null ? null : context.tenantId());
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(tenantId)
                .action("tenant.currency.update")
                .resourceType("tnt_tenant_config")
                .resourceId(tenantId == null ? null : String.valueOf(tenantId))
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"requestedCurrency\":"
                        + (request == null || request.currencyCode() == null
                                ? "null" : "\"" + request.currencyCode() + "\"")
                        + "}")
                .build());
    }

    private static String detailJson(CurrencySwitchResult result) {
        // 值域全部是受控的枚举码与整数（无用户自由文本），直接拼装避免额外类型转换链。
        return "{\"before\":\"" + result.before().code() + "\""
                + ",\"after\":\"" + result.after().code() + "\""
                + ",\"storesUpdated\":" + result.storesUpdated()
                + ",\"walletsMigrated\":" + result.walletsMigrated()
                + ",\"walletsSkipped\":" + result.walletsSkipped()
                + ",\"walletsAffected\":" + result.walletsAffected() + "}";
    }

    private static CurrencyView view(Currency currency) {
        List<SupportedCurrency> supported = Arrays.stream(Currency.values())
                .map(item -> new SupportedCurrency(item.code(), item.symbol(), item.displayName()))
                .toList();
        return new CurrencyView(currency.code(), currency.symbol(), currency.minorUnitDigits(), supported);
    }

    /**
     * 租户边界：请求里的 tenantId 只能与上下文一致（缺省时直接用上下文租户）。
     * 与 {@link TenantConfigController#get} 同款写法，杜绝「按参数读任意租户」。
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

    /** 币种视图：`{currencyCode, symbol, minorUnitDigits, supported}`。 */
    public record CurrencyView(String currencyCode, String symbol, int minorUnitDigits,
                              List<SupportedCurrency> supported) {
    }

    /** 受支持币种字典项。 */
    public record SupportedCurrency(String code, String symbol, String label) {
    }

    /** 切换请求：{@code migrateBalances} 显式 true 才改写非零余额钱包账户币种（金额数字不变）。 */
    public record UpdateCurrencyRequest(String currencyCode, Boolean migrateBalances) {
    }
}
