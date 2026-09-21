package com.gvchat.platform.tenant.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.tenant.api.controller.TenantCurrencyController.CurrencyView;
import com.gvchat.platform.tenant.api.controller.TenantCurrencyController.UpdateCurrencyRequest;
import com.gvchat.platform.tenant.application.TenantCurrencyApplicationService;
import com.gvchat.platform.tenant.application.WalletTokenConfigApplicationService;
import com.gvchat.platform.tenant.infra.persistence.mapper.InternalTenantConfigMapper;
import com.gvchat.platform.tenant.infra.persistence.mapper.StoreMapper;
import com.gvchat.platform.tenant.infra.persistence.mapper.TenantConfigMapper;
import com.gvchat.platform.tenant.infra.persistence.mapper.WalletAccountCurrencyMapper;
import com.gvchat.platform.tenant.infra.persistence.po.StorePo;
import com.gvchat.platform.tenant.infra.persistence.po.TenantConfigPo;
import com.gvchat.platform.tenant.infra.persistence.po.WalletAccountCurrencyPo;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 租户币种配置：缺省 USD、读写往返、非法值 400、权限 403、越权 tenantId 拒绝、
 * 门店写穿、钱包余额拦截与 migrateBalances 改写、审计含前后值（规范 §2 / §2.2 / §7）。
 */
class TenantCurrencyControllerTest {

    private static final long TENANT_ID = 100L;

    private TenantConfigMapper tenantConfigMapper;
    private InternalTenantConfigMapper internalTenantConfigMapper;
    private StoreMapper storeMapper;
    private WalletAccountCurrencyMapper walletAccountMapper;
    private AuditClient auditClient;
    private TenantCurrencyController controller;

    @BeforeEach
    void setUp() {
        tenantConfigMapper = mock(TenantConfigMapper.class);
        internalTenantConfigMapper = mock(InternalTenantConfigMapper.class);
        storeMapper = mock(StoreMapper.class);
        walletAccountMapper = mock(WalletAccountCurrencyMapper.class);
        auditClient = mock(AuditClient.class);
        when(auditClient.recordAsync(any())).thenReturn(CompletableFuture.completedFuture(1L));
        TenantCurrencyApplicationService service = new TenantCurrencyApplicationService(
                tenantConfigMapper, internalTenantConfigMapper, storeMapper, walletAccountMapper);
        controller = new TenantCurrencyController(service, auditClient);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /** 缺省（无配置行）必须解析为 USD，并给出完整字典。 */
    @Test
    void getDefaultsToUsdWhenTenantHasNoCurrencyConfig() {
        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "currency")).thenReturn(null);
        withContext();

        CurrencyView view = controller.get(null);

        assertThat(view.currencyCode()).isEqualTo("USD");
        assertThat(view.symbol()).isEqualTo("$");
        assertThat(view.minorUnitDigits()).isEqualTo(2);
        assertThat(view.supported()).hasSize(2);
        assertThat(view.supported().get(0).code()).isEqualTo("CNY");
        assertThat(view.supported().get(0).symbol()).isEqualTo("¥");
        assertThat(view.supported().get(0).label()).isEqualTo("人民币");
        assertThat(view.supported().get(1).code()).isEqualTo("USD");
    }

    /** 空值与非法存量值都不能抛给业务，一律 USD。 */
    @Test
    void getFallsBackToUsdForBlankOrUnknownStoredValue() {
        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "currency")).thenReturn("  ");
        withContext();
        assertThat(controller.get(null).currencyCode()).isEqualTo("USD");

        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "currency")).thenReturn("RMB");
        assertThat(controller.get(null).currencyCode()).isEqualTo("USD");
    }

    /** 读写往返：PUT CNY 落库 + 门店写穿；随后 GET 读到 CNY。 */
    @Test
    void updateWritesConfigAndWritesThroughStoresThenGetReturnsIt() {
        when(tenantConfigMapper.selectList(any())).thenReturn(List.of());
        when(storeMapper.selectList(any())).thenReturn(List.of(store(1L, "CNY"), store(2L, null), store(3L, "CNY")));
        when(walletAccountMapper.selectList(any())).thenReturn(List.of());
        withContext("tenant.currency.manage");

        CurrencyView updated = controller.update(null, new UpdateCurrencyRequest("CNY", null));

        assertThat(updated.currencyCode()).isEqualTo("CNY");
        assertThat(updated.symbol()).isEqualTo("¥");
        ArgumentCaptor<TenantConfigPo> inserted = ArgumentCaptor.forClass(TenantConfigPo.class);
        verify(tenantConfigMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getConfigKey()).isEqualTo("currency");
        assertThat(inserted.getValue().getConfigValue()).isEqualTo("CNY");
        assertThat(inserted.getValue().getStoreId()).isZero();
        // §2.2.1 门店写穿：三个门店全部改为 CNY（含原本为 null 的）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<StorePo>> storePatch =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(storeMapper).update(any(StorePo.class), storePatch.capture());
        assertThat(storePatch.getValue().getSqlSegment()).contains("id");

        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "currency")).thenReturn("CNY");
        assertThat(controller.get(null).currencyCode()).isEqualTo("CNY");
    }

    /** 已存在配置行时走 update，不得重复插入。 */
    @Test
    void updateReusesExistingConfigRow() {
        TenantConfigPo existing = new TenantConfigPo();
        existing.setId(9L);
        existing.setConfigValue("CNY");
        when(tenantConfigMapper.selectList(any())).thenReturn(List.of(existing));
        when(storeMapper.selectList(any())).thenReturn(List.of());
        when(walletAccountMapper.selectList(any())).thenReturn(List.of());
        withContext("tenant.currency.manage");

        assertThat(controller.update(null, new UpdateCurrencyRequest("USD", null)).currencyCode()).isEqualTo("USD");
        verify(tenantConfigMapper, never()).insert(any(TenantConfigPo.class));
        verify(tenantConfigMapper).updateById(existing);
        assertThat(existing.getConfigValue()).isEqualTo("USD");
    }

    /** 不在 {CNY,USD} → 400 CURRENCY_UNSUPPORTED，且不落库。 */
    @Test
    void updateRejectsUnsupportedCurrencyWith400() {
        withContext("tenant.currency.manage");

        assertThatThrownBy(() -> controller.update(null, new UpdateCurrencyRequest("JPY", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(400);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("CURRENCY_UNSUPPORTED");
                });
        assertThatThrownBy(() -> controller.update(null, new UpdateCurrencyRequest(null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("CURRENCY_UNSUPPORTED"));
        verify(tenantConfigMapper, never()).insert(any(TenantConfigPo.class));
        verify(tenantConfigMapper, never()).updateById(any(TenantConfigPo.class));
    }

    /** 写权限缺失 → 403 PERMISSION_DENIED；读不需要该权限。 */
    @Test
    void updateRequiresManagePermission() {
        withContext();
        assertThatThrownBy(() -> controller.update(null, new UpdateCurrencyRequest("USD", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(403);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("PERMISSION_DENIED");
                });
        assertThat(controller.get(null)).isNotNull();
    }

    /** 传入与上下文不一致的 tenantId 一律 403，绝不按参数选租户。 */
    @Test
    void rejectsCrossTenantTenantIdParameter() {
        withContext("tenant.currency.manage");

        assertThatThrownBy(() -> controller.get(200L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(403);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("TENANT_SCOPE_DENIED");
                });
        assertThatThrownBy(() -> controller.update(200L, new UpdateCurrencyRequest("USD", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("TENANT_SCOPE_DENIED"));
        // 与上下文一致的 tenantId 是允许的（向后兼容表单/脚本传参）
        assertThat(controller.get(TENANT_ID).currencyCode()).isEqualTo("USD");
    }

    /** 无租户上下文 → 401 SAAS_CONTEXT_REQUIRED。 */
    @Test
    void requiresTenantContext() {
        assertThatThrownBy(() -> controller.get(null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(401);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("SAAS_CONTEXT_REQUIRED");
                });
        assertThatThrownBy(() -> controller.update(null, new UpdateCurrencyRequest("USD", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(401));
    }

    /** 审计 detail 必须含变更前/后值与受影响行数（门店写穿 + 钱包账户）。 */
    @Test
    void auditDetailContainsBeforeAfterAndAffectedRows() {
        when(tenantConfigMapper.selectList(any())).thenReturn(List.of());
        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "currency")).thenReturn("CNY");
        when(storeMapper.selectList(any())).thenReturn(List.of(store(1L, "CNY"), store(2L, "CNY")));
        when(walletAccountMapper.selectList(any())).thenReturn(List.of(wallet(7L, "CNY", 1500L, 0L)));
        when(walletAccountMapper.selectCount(any())).thenReturn(0L);
        withContext("tenant.currency.manage");

        controller.update(null, new UpdateCurrencyRequest("USD", true));

        ArgumentCaptor<AuditClient.AuditRecord> record = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(record.capture());
        assertThat(record.getValue().action()).isEqualTo("tenant.currency.update");
        assertThat(record.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(record.getValue().detailJson())
                .contains("\"before\":\"CNY\"")
                .contains("\"after\":\"USD\"")
                .contains("\"storesUpdated\":2")
                .contains("\"walletsMigrated\":1")
                .contains("\"walletsAffected\":1");
    }

    /**
     * ③ 失败留痕：余额阻断（409 CURRENCY_SWITCH_BLOCKED_BY_BALANCE）是运营最常遇到的「改不动币种」，
     * 必须落 FAILURE 且带稳定错误码；失败记录不得改变业务结果（异常照旧抛出）。
     */
    @Test
    void failedCurrencySwitchRecordsFailureAuditWithErrorCode() {
        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "currency")).thenReturn("CNY");
        when(walletAccountMapper.selectList(any())).thenReturn(List.of(wallet(7L, "CNY", 1500L, 0L)));
        withContext("tenant.currency.manage");

        assertThatThrownBy(() -> controller.update(null, new UpdateCurrencyRequest("USD", null)))
                .isInstanceOf(ApiException.class);

        ArgumentCaptor<AuditClient.AuditRecord> record = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(record.capture());
        assertThat(record.getValue().action()).isEqualTo("tenant.currency.update");
        assertThat(record.getValue().result()).isEqualTo(AuditClient.AuditRecord.RESULT_FAILURE);
        assertThat(record.getValue().errorCode()).isEqualTo("CURRENCY_SWITCH_BLOCKED_BY_BALANCE");
        assertThat(record.getValue().detailJson()).contains("\"requestedCurrency\":\"USD\"");
    }

    /** ③ 失败留痕：非法币种（400 CURRENCY_UNSUPPORTED）、缺权限（403）同样必须留痕。 */
    @Test
    void invalidCurrencyAndMissingPermissionAreAuditedAsFailure() {
        withContext("tenant.currency.manage");
        assertThatThrownBy(() -> controller.update(null, new UpdateCurrencyRequest("JPY", null)))
                .isInstanceOf(ApiException.class);
        ArgumentCaptor<AuditClient.AuditRecord> invalid = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(invalid.capture());
        assertThat(invalid.getValue().result()).isEqualTo(AuditClient.AuditRecord.RESULT_FAILURE);
        assertThat(invalid.getValue().errorCode()).isEqualTo("CURRENCY_UNSUPPORTED");

        // 缺权限：换一个干净的审计 mock，避免与上一条记录混淆。
        AuditClient permissionClient = mock(AuditClient.class);
        TenantCurrencyController permissionController = new TenantCurrencyController(
                new TenantCurrencyApplicationService(tenantConfigMapper, internalTenantConfigMapper,
                        storeMapper, walletAccountMapper), permissionClient);
        withContext();
        assertThatThrownBy(() -> permissionController.update(null, new UpdateCurrencyRequest("USD", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("PERMISSION_DENIED"));

        ArgumentCaptor<AuditClient.AuditRecord> denied = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(permissionClient).recordAsync(denied.capture());
        assertThat(denied.getValue().result()).isEqualTo(AuditClient.AuditRecord.RESULT_FAILURE);
        assertThat(denied.getValue().errorCode()).isEqualTo("PERMISSION_DENIED");
    }

    /** §2.2.2 默认阻断：存在非零余额且币种不同的钱包账户 → 409，且不改任何配置。 */
    @Test
    void blocksCurrencySwitchWhenWalletBalanceHasDifferentCurrency() {
        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "currency")).thenReturn("CNY");
        when(walletAccountMapper.selectList(any())).thenReturn(List.of(
                wallet(7L, "CNY", 1500L, 0L), wallet(8L, "CNY", 0L, 500L)));
        withContext("tenant.currency.manage");

        assertThatThrownBy(() -> controller.update(null, new UpdateCurrencyRequest("USD", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("2")
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(409);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("CURRENCY_SWITCH_BLOCKED_BY_BALANCE");
                });
        verify(tenantConfigMapper, never()).insert(any(TenantConfigPo.class));
        verify(storeMapper, never()).update(any(StorePo.class), any());
        verify(walletAccountMapper, never()).updateById(any(WalletAccountCurrencyPo.class));
    }

    /** §2.2.2 显式 migrateBalances=true：金额数字不变，只改币种并留痕。 */
    @Test
    void migratesWalletBalancesOnlyWhenExplicitlyRequested() {
        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "currency")).thenReturn("CNY");
        when(tenantConfigMapper.selectList(any())).thenReturn(List.of());
        when(storeMapper.selectList(any())).thenReturn(List.of());
        WalletAccountCurrencyPo account = wallet(7L, "CNY", 1500L, 0L);
        when(walletAccountMapper.selectList(any())).thenReturn(List.of(account));
        when(walletAccountMapper.selectCount(any())).thenReturn(0L);
        withContext("tenant.currency.manage");

        controller.update(null, new UpdateCurrencyRequest("USD", true));

        ArgumentCaptor<WalletAccountCurrencyPo> patch = ArgumentCaptor.forClass(WalletAccountCurrencyPo.class);
        verify(walletAccountMapper).updateById(patch.capture());
        assertThat(patch.getValue().getId()).isEqualTo(7L);
        assertThat(patch.getValue().getCurrencyCode()).isEqualTo("USD");
        // 金额列绝不参与改写：补丁对象不得带金额（金额数字不变）
        assertThat(patch.getValue().getAvailableAmount()).isNull();
        assertThat(patch.getValue().getFrozenAmount()).isNull();
    }

    /** 同主体已有目标币种账户时跳过改写（禁止事实上的跨币种合并），并计入审计。 */
    @Test
    void skipsWalletMigrationWhenTargetCurrencyAccountAlreadyExists() {
        when(internalTenantConfigMapper.selectTenantConfigValue(TENANT_ID, "currency")).thenReturn("CNY");
        when(tenantConfigMapper.selectList(any())).thenReturn(List.of());
        when(storeMapper.selectList(any())).thenReturn(List.of());
        when(walletAccountMapper.selectList(any())).thenReturn(List.of(wallet(7L, "CNY", 1500L, 0L)));
        when(walletAccountMapper.selectCount(any())).thenReturn(1L);
        withContext("tenant.currency.manage");

        controller.update(null, new UpdateCurrencyRequest("USD", true));

        verify(walletAccountMapper, never()).updateById(any(WalletAccountCurrencyPo.class));
        ArgumentCaptor<AuditClient.AuditRecord> record = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(record.capture());
        assertThat(record.getValue().detailJson())
                .contains("\"walletsMigrated\":0")
                .contains("\"walletsSkipped\":1");
    }

    /** 内部端点（签发上下文时读目标租户币种）：缺省 USD、配置后按配置。 */
    @Test
    void internalEndpointResolvesTargetTenantCurrency() {
        InternalTenantConfigController internal = new InternalTenantConfigController(
                new TenantCurrencyApplicationService(tenantConfigMapper, internalTenantConfigMapper,
                        storeMapper, walletAccountMapper),
                new WalletTokenConfigApplicationService(internalTenantConfigMapper));
        when(internalTenantConfigMapper.selectTenantConfigValue(200L, "currency")).thenReturn(null);
        assertThat(internal.tenantCurrency(200L).currencyCode()).isEqualTo("USD");

        when(internalTenantConfigMapper.selectTenantConfigValue(200L, "currency")).thenReturn("CNY");
        assertThat(internal.tenantCurrency(200L).currencyCode()).isEqualTo("CNY");
        assertThat(internal.tenantCurrency(200L).tenantId()).isEqualTo(200L);
    }

    private void withContext(String... permissions) {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, 1L, 1, List.of(permissions)));
    }

    private static StorePo store(Long id, String currency) {
        StorePo store = new StorePo();
        store.setId(id);
        store.setTenantId(TENANT_ID);
        store.setDefaultCurrency(currency);
        return store;
    }

    private static WalletAccountCurrencyPo wallet(Long id, String currency, Long available, Long frozen) {
        WalletAccountCurrencyPo wallet = new WalletAccountCurrencyPo();
        wallet.setId(id);
        wallet.setTenantId(TENANT_ID);
        wallet.setCustomerId(id * 10);
        wallet.setLegalEntityId(1L);
        wallet.setCurrencyCode(currency);
        wallet.setAvailableAmount(available);
        wallet.setFrozenAmount(frozen);
        return wallet;
    }
}
