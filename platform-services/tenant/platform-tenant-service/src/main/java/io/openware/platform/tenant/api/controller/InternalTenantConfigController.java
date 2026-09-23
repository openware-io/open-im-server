package io.openware.platform.tenant.api.controller;

import io.openware.infrastructure.currency.Currency;
import io.openware.platform.tenant.application.TenantCurrencyApplicationService;
import io.openware.platform.tenant.application.WalletTokenConfigApplicationService;
import io.openware.platform.tenant.application.WalletTokenConfigApplicationService.TenantWalletTokenConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户配置内部端点：供 identity-service / platform-admin-service 在 **context select 签发上下文**时
 * 读取「目标租户」的币种（`docs/standards/16_CURRENCY_CONVENTIONS.md` §3.1），
 * 以及供 platform-customer-service 读取「目标租户」的钱包代币展示配置（品牌名 + ratio，§9）。
 *
 * <p>为什么放在 `/internal/iam/` 前缀下：该前缀由 {@code InternalServiceAuthenticationFilter} 统一要求
 * 内部服务签名（source/签名/时间戳/请求 ID），复用既有内部调用通道，不必新增一套鉴权；
 * 客户端无法通过网关访问 `/internal/**`。调用方 source 必须列在
 * {@code internal.service-auth.expected-source} 白名单内（缺省含 platform-customer-service）。
 *
 * <p>读路径一律缺省（币种 USD、代币 {@code A380币 / 100}）、绝不抛异常：签发上下文不能因为币种查询失败
 * 而失败，C 端余额/流水展示也不能因为租户代币配置读取失败而失败。
 */
@RestController
@RequestMapping("/internal/iam/tenant-config")
public class InternalTenantConfigController {

    private final TenantCurrencyApplicationService currencyService;
    private final WalletTokenConfigApplicationService walletTokenConfigService;

    public InternalTenantConfigController(TenantCurrencyApplicationService currencyService,
                                          WalletTokenConfigApplicationService walletTokenConfigService) {
        this.currencyService = currencyService;
        this.walletTokenConfigService = walletTokenConfigService;
    }

    /** 目标租户币种；无配置/非法值回退 USD。 */
    @GetMapping("/{tenantId}/currency")
    public InternalTenantCurrency tenantCurrency(@PathVariable Long tenantId) {
        Currency currency = currencyService.resolve(tenantId);
        return new InternalTenantCurrency(tenantId, currency.code());
    }

    /**
     * 目标租户的钱包代币展示配置（品牌展示名 + ratio）；无配置/非法值回退 {@code A380币 / 100}。
     *
     * <p>调用方按 {@code 代币数量 = 余额最小单位 ÷ 100 × ratio} 换算<b>展示</b>数量；
     * ratio 禁止参与任何金额计算（§9）。
     */
    @GetMapping("/{tenantId}/wallet-token")
    public InternalTenantWalletToken tenantWalletToken(@PathVariable Long tenantId) {
        TenantWalletTokenConfig config = walletTokenConfigService.resolve(tenantId);
        return new InternalTenantWalletToken(tenantId, config.brandName(), config.ratio());
    }

    /** 内部契约 DTO（字段名与对外 JSON 统一为 currencyCode）。 */
    public record InternalTenantCurrency(Long tenantId, String currencyCode) {
    }

    /**
     * 内部契约 DTO：代币展示配置。
     * {@code brandName} 是展示文案（不是币种），{@code ratio} 只用于展示代币数量，禁止入账。
     */
    public record InternalTenantWalletToken(Long tenantId, String brandName, long ratio) {
    }
}
