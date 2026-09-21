package com.gvchat.platform.tenant.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gvchat.platform.tenant.api.controller.InternalTenantConfigController.InternalTenantWalletToken;
import com.gvchat.platform.tenant.application.TenantCurrencyApplicationService;
import com.gvchat.platform.tenant.application.WalletTokenConfigApplicationService;
import com.gvchat.platform.tenant.infra.persistence.mapper.InternalTenantConfigMapper;
import com.gvchat.platform.tenant.infra.persistence.mapper.StoreMapper;
import com.gvchat.platform.tenant.infra.persistence.mapper.TenantConfigMapper;
import com.gvchat.platform.tenant.infra.persistence.mapper.WalletAccountCurrencyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 内部端点 {@code GET /internal/iam/tenant-config/{tenantId}/wallet-token}：
 * 供 platform-customer-service 读取「目标租户」的钱包代币展示配置（品牌名 + ratio）。
 *
 * <p>口径：缺行 → {@code A380币/100}；空值/非数字/非正数 → {@code A380币/100}；
 * 非法 tenantId → {@code A380币/100}；读路径绝不抛异常（C 端余额/流水展示不能因此失败）。
 */
class InternalWalletTokenConfigControllerTest {

    private static final long TENANT_ID = 100L;

    private InternalTenantConfigMapper internalTenantConfigMapper;
    private InternalTenantConfigController controller;

    @BeforeEach
    void setUp() {
        internalTenantConfigMapper = mock(InternalTenantConfigMapper.class);
        TenantCurrencyApplicationService currencyService = new TenantCurrencyApplicationService(
                mock(TenantConfigMapper.class), internalTenantConfigMapper, mock(StoreMapper.class),
                mock(WalletAccountCurrencyMapper.class));
        controller = new InternalTenantConfigController(currencyService,
                new WalletTokenConfigApplicationService(internalTenantConfigMapper));
    }

    /** 有配置：按租户配置返回品牌名与比例（自定义品牌，如「皇冠币」）。 */
    @Test
    void returnsConfiguredBrandNameAndRatio() {
        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "wallet_brand_name")).thenReturn("皇冠币");
        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "wallet_ratio")).thenReturn("500");

        InternalTenantWalletToken token = controller.tenantWalletToken(TENANT_ID);

        assertThat(token.tenantId()).isEqualTo(TENANT_ID);
        assertThat(token.brandName()).isEqualTo("皇冠币");
        assertThat(token.ratio()).isEqualTo(500L);
    }

    /** 无配置（缺行）：回退缺省 A380币 / 100。 */
    @Test
    void fallsBackToDefaultsWhenTenantHasNoConfig() {
        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "wallet_brand_name")).thenReturn(null);
        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "wallet_ratio")).thenReturn(null);

        InternalTenantWalletToken token = controller.tenantWalletToken(TENANT_ID);

        assertThat(token.brandName()).isEqualTo("A380币");
        assertThat(token.ratio()).isEqualTo(100L);
    }

    /** 非法/空值：品牌空白、比例非数字/零/负数一律回退缺省，且不抛异常。 */
    @Test
    void fallsBackToDefaultsForBlankBrandAndInvalidRatio() {
        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "wallet_brand_name")).thenReturn("   ");
        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "wallet_ratio")).thenReturn("abc");
        InternalTenantWalletToken notANumber = controller.tenantWalletToken(TENANT_ID);
        assertThat(notANumber.brandName()).isEqualTo("A380币");
        assertThat(notANumber.ratio()).isEqualTo(100L);

        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "wallet_ratio")).thenReturn("0");
        assertThat(controller.tenantWalletToken(TENANT_ID).ratio()).isEqualTo(100L);

        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "wallet_ratio")).thenReturn("-5");
        assertThat(controller.tenantWalletToken(TENANT_ID).ratio()).isEqualTo(100L);

        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "wallet_ratio")).thenReturn("");
        assertThat(controller.tenantWalletToken(TENANT_ID).ratio()).isEqualTo(100L);
    }

    /** 品牌名去除首尾空白后返回（展示文案不允许带无意义空白）。 */
    @Test
    void trimsConfiguredBrandName() {
        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "wallet_brand_name")).thenReturn(" 储值币 ");
        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "wallet_ratio")).thenReturn(" 200 ");

        InternalTenantWalletToken token = controller.tenantWalletToken(TENANT_ID);

        assertThat(token.brandName()).isEqualTo("储值币");
        assertThat(token.ratio()).isEqualTo(200L);
    }

    /** 非法 tenantId（null / 非正）不查询配置、直接回退缺省，不抛异常。 */
    @Test
    void fallsBackForInvalidTenantIdWithoutQuerying() {
        assertThat(controller.tenantWalletToken(null).brandName()).isEqualTo("A380币");
        assertThat(controller.tenantWalletToken(null).ratio()).isEqualTo(100L);
        assertThat(controller.tenantWalletToken(0L).ratio()).isEqualTo(100L);
        assertThat(controller.tenantWalletToken(-1L).brandName()).isEqualTo("A380币");
    }
}
