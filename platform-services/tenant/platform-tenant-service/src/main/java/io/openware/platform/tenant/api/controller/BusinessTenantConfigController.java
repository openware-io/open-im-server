package io.openware.platform.tenant.api.controller;

import io.openware.infrastructure.tenant.ClientAudience;
import io.openware.platform.tenant.application.WalletTokenConfigApplicationService;
import io.openware.platform.tenant.application.WalletTokenConfigApplicationService.TenantWalletTokenConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端只读租户钱包展示配置；租户来自签名上下文，禁止跨租户。 */
@ClientAudience("saas-c")
@RestController
@RequestMapping("/business/tenant/config")
public class BusinessTenantConfigController {

    private final WalletTokenConfigApplicationService walletTokenConfigService;

    public BusinessTenantConfigController(WalletTokenConfigApplicationService walletTokenConfigService) {
        this.walletTokenConfigService = walletTokenConfigService;
    }

    @GetMapping
    public TenantConfig get() {
        TenantWalletTokenConfig config = walletTokenConfigService.resolveScoped(null);
        return new TenantConfig(config.brandName(), config.ratio());
    }

    public record TenantConfig(String brandName, long ratio) {
    }
}
