package io.openware.platform.tenant.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.platform.tenant.api.controller.InternalTenantConfigController.InternalTenantWalletToken;
import io.openware.platform.tenant.application.TenantCurrencyApplicationService;
import io.openware.platform.tenant.application.WalletTokenConfigApplicationService;
import io.openware.platform.tenant.domain.configuration.repository.TenantConfigReader;
import io.openware.platform.tenant.domain.configuration.repository.TenantConfigRepository;
import io.openware.platform.tenant.domain.currency.repository.WalletAccountCurrencyPort;
import io.openware.platform.tenant.domain.store.repository.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 内部端点 {@code GET /internal/iam/tenant-config/{tenantId}/wallet-token}：
 * 供 platform-customer-service 读取「目标租户」的钱包代币展示配置（品牌名 + ratio）。
 *
 * <p>口径：缺行 → {@code SaaS 币/100}；空值/非数字/非正数 → {@code SaaS 币/100}；
 * 非法 tenantId → {@code SaaS 币/100}；读路径绝不抛异常（C 端余额/流水展示不能因此失败）。
 */
class InternalWalletTokenConfigControllerTest {

    private static final long TENANT_ID = 100L;

    private TenantConfigReader tenantConfigReader;
    private InternalTenantConfigController controller;

    @BeforeEach
    void setUp() {
        tenantConfigReader = mock(TenantConfigReader.class);
        TenantCurrencyApplicationService currencyService = new TenantCurrencyApplicationService(
                mock(TenantConfigReader.class), mock(TenantConfigRepository.class), mock(StoreRepository.class),
                mock(WalletAccountCurrencyPort.class));
        controller = new InternalTenantConfigController(currencyService,
                new WalletTokenConfigApplicationService(tenantConfigReader));
    }

    /** 有配置：按租户配置返回品牌名与比例（自定义品牌，如「皇冠币」）。 */
    @Test
    void returnsConfiguredBrandNameAndRatio() {
        when(tenantConfigReader.findValue(TENANT_ID, 0L, "wallet_brand_name")).thenReturn(java.util.Optional.of("皇冠币"));
        when(tenantConfigReader.findValue(TENANT_ID, 0L, "wallet_ratio")).thenReturn(java.util.Optional.of("500"));

        InternalTenantWalletToken token = controller.tenantWalletToken(TENANT_ID);

        assertThat(token.tenantId()).isEqualTo(TENANT_ID);
        assertThat(token.brandName()).isEqualTo("皇冠币");
        assertThat(token.ratio()).isEqualTo(500L);
    }

    /** 无配置（缺行）：回退缺省 SaaS 币 / 100。 */
    @Test
    void fallsBackToDefaultsWhenTenantHasNoConfig() {
        when(tenantConfigReader.findValue(TENANT_ID, 0L, "wallet_brand_name")).thenReturn(java.util.Optional.empty());
        when(tenantConfigReader.findValue(TENANT_ID, 0L, "wallet_ratio")).thenReturn(java.util.Optional.empty());

        InternalTenantWalletToken token = controller.tenantWalletToken(TENANT_ID);

        assertThat(token.brandName()).isEqualTo("SaaS 币");
        assertThat(token.ratio()).isEqualTo(100L);
    }

    /** 非法/空值：品牌空白、比例非数字/零/负数一律回退缺省，且不抛异常。 */
    @Test
    void fallsBackToDefaultsForBlankBrandAndInvalidRatio() {
        when(tenantConfigReader.findValue(TENANT_ID, 0L, "wallet_brand_name")).thenReturn(java.util.Optional.of("   "));
        when(tenantConfigReader.findValue(TENANT_ID, 0L, "wallet_ratio")).thenReturn(java.util.Optional.of("abc"));
        InternalTenantWalletToken notANumber = controller.tenantWalletToken(TENANT_ID);
        assertThat(notANumber.brandName()).isEqualTo("SaaS 币");
        assertThat(notANumber.ratio()).isEqualTo(100L);

        when(tenantConfigReader.findValue(TENANT_ID, 0L, "wallet_ratio")).thenReturn(java.util.Optional.of("0"));
        assertThat(controller.tenantWalletToken(TENANT_ID).ratio()).isEqualTo(100L);

        when(tenantConfigReader.findValue(TENANT_ID, 0L, "wallet_ratio")).thenReturn(java.util.Optional.of("-5"));
        assertThat(controller.tenantWalletToken(TENANT_ID).ratio()).isEqualTo(100L);

        when(tenantConfigReader.findValue(TENANT_ID, 0L, "wallet_ratio")).thenReturn(java.util.Optional.of(""));
        assertThat(controller.tenantWalletToken(TENANT_ID).ratio()).isEqualTo(100L);
    }

    /** 品牌名去除首尾空白后返回（展示文案不允许带无意义空白）。 */
    @Test
    void trimsConfiguredBrandName() {
        when(tenantConfigReader.findValue(TENANT_ID, 0L, "wallet_brand_name")).thenReturn(java.util.Optional.of(" 储值币 "));
        when(tenantConfigReader.findValue(TENANT_ID, 0L, "wallet_ratio")).thenReturn(java.util.Optional.of(" 200 "));

        InternalTenantWalletToken token = controller.tenantWalletToken(TENANT_ID);

        assertThat(token.brandName()).isEqualTo("储值币");
        assertThat(token.ratio()).isEqualTo(200L);
    }

    /** 非法 tenantId（null / 非正）不查询配置、直接回退缺省，不抛异常。 */
    @Test
    void fallsBackForInvalidTenantIdWithoutQuerying() {
        assertThat(controller.tenantWalletToken(null).brandName()).isEqualTo("SaaS 币");
        assertThat(controller.tenantWalletToken(null).ratio()).isEqualTo(100L);
        assertThat(controller.tenantWalletToken(0L).ratio()).isEqualTo(100L);
        assertThat(controller.tenantWalletToken(-1L).brandName()).isEqualTo("SaaS 币");
    }
}
