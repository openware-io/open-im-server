package io.openware.platform.tenant.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.api.controller.TenantCurrencyController.CurrencyView;
import io.openware.platform.tenant.api.controller.TenantCurrencyController.UpdateCurrencyRequest;
import io.openware.platform.tenant.application.TenantCurrencyApplicationService;
import io.openware.platform.tenant.application.WalletTokenConfigApplicationService;
import io.openware.platform.tenant.domain.configuration.model.TenantConfig;
import io.openware.platform.tenant.domain.configuration.repository.TenantConfigReader;
import io.openware.platform.tenant.domain.configuration.repository.TenantConfigRepository;
import io.openware.platform.tenant.domain.currency.model.WalletAccount;
import io.openware.platform.tenant.domain.currency.repository.WalletAccountCurrencyPort;
import io.openware.platform.tenant.domain.store.repository.StoreRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 租户币种配置：缺省 USD、读写往返、非法值 400、权限 403、越权 tenantId 拒绝、
 * 门店写穿、钱包余额拦截与 migrateBalances 改写、审计含前后值（规范 §2 / §2.2 / §7）。
 *
 * <p><b>分层后的断言归属</b>：此前本文件断言"写穿的补丁 Wrapper 里含 id 条件""钱包补丁对象不带金额"
 * ——那是适配器如何拼 SQL 的细节。现在补丁由端口按列表达，"金额列不参与"是**端口签名本身**的约束
 * （{@code changeCurrency} 只收账户标识与目标币种，没有金额参数）；本文件改为断言传给端口的实参。
 * 金额不被改写的保真度没有丢，只是从"断言补丁字段为空"变成了"无法表达改写金额"。
 */
class TenantCurrencyControllerTest {

    private static final long TENANT_ID = 100L;

    private TenantConfigReader tenantConfigReader;
    private TenantConfigRepository tenantConfigRepository;
    private StoreRepository storeRepository;
    private WalletAccountCurrencyPort walletAccountCurrencyPort;
    private AuditClient auditClient;
    private TenantCurrencyController controller;

    @BeforeEach
    void setUp() {
        tenantConfigReader = mock(TenantConfigReader.class);
        tenantConfigRepository = mock(TenantConfigRepository.class);
        storeRepository = mock(StoreRepository.class);
        walletAccountCurrencyPort = mock(WalletAccountCurrencyPort.class);
        auditClient = mock(AuditClient.class);
        when(auditClient.recordAsync(any())).thenReturn(CompletableFuture.completedFuture(1L));
        when(tenantConfigRepository.upsert(any(TenantConfig.class))).thenAnswer(i -> i.getArgument(0));
        controller = new TenantCurrencyController(newService(), auditClient);
    }

    private TenantCurrencyApplicationService newService() {
        return new TenantCurrencyApplicationService(tenantConfigReader, tenantConfigRepository,
                storeRepository, walletAccountCurrencyPort);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /** 缺省（无配置行）必须解析为 USD，并给出完整字典。 */
    @Test
    void getDefaultsToUsdWhenTenantHasNoCurrencyConfig() {
        when(tenantConfigReader.findValue(TENANT_ID, 0L, "currency")).thenReturn(Optional.empty());
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
        when(tenantConfigReader.findValue(TENANT_ID, 0L, "currency")).thenReturn(Optional.of("  "));
        withContext();
        assertThat(controller.get(null).currencyCode()).isEqualTo("USD");

        when(tenantConfigReader.findValue(TENANT_ID, 0L, "currency")).thenReturn(Optional.of("RMB"));
        assertThat(controller.get(null).currencyCode()).isEqualTo("USD");
    }

    /** 读写往返：PUT CNY 落库 + 门店写穿；随后 GET 读到 CNY。 */
    @Test
    void updateWritesConfigAndWritesThroughStoresThenGetReturnsIt() {
        when(storeRepository.changeDefaultCurrency(TENANT_ID, "CNY")).thenReturn(3);
        when(walletAccountCurrencyPort.listNonZeroInOtherCurrency(TENANT_ID, "CNY")).thenReturn(List.of());
        withContext("tenant.currency.manage");

        CurrencyView updated = controller.update(null, new UpdateCurrencyRequest("CNY", null));

        assertThat(updated.currencyCode()).isEqualTo("CNY");
        assertThat(updated.symbol()).isEqualTo("¥");
        // 配置落库：租户级（store_id=0）的 currency 键
        ArgumentCaptor<TenantConfig> upserted = ArgumentCaptor.forClass(TenantConfig.class);
        verify(tenantConfigRepository).upsert(upserted.capture());
        assertThat(upserted.getValue().configKey()).isEqualTo("currency");
        assertThat(upserted.getValue().configValue()).isEqualTo("CNY");
        assertThat(upserted.getValue().storeId()).isZero();
        // §2.2.1 门店写穿：按租户整批改币种，返回实际受影响门店数
        verify(storeRepository).changeDefaultCurrency(TENANT_ID, "CNY");

        when(tenantConfigReader.findValue(TENANT_ID, 0L, "currency")).thenReturn(Optional.of("CNY"));
        assertThat(controller.get(null).currencyCode()).isEqualTo("CNY");
    }

    /** 已存在配置行时走更新，不得重复插入（upsert 语义由端口承担）。 */
    @Test
    void updateReusesExistingConfigRow() {
        when(storeRepository.changeDefaultCurrency(TENANT_ID, "USD")).thenReturn(0);
        when(walletAccountCurrencyPort.listNonZeroInOtherCurrency(TENANT_ID, "USD")).thenReturn(List.of());
        when(tenantConfigRepository.findByScope(TENANT_ID, 0L, null, "currency"))
                .thenReturn(Optional.of(new TenantConfig(9L, TENANT_ID, 0L, "", "currency", "CNY",
                        TenantConfig.STATUS_ACTIVE, 0)));
        withContext("tenant.currency.manage");

        assertThat(controller.update(null, new UpdateCurrencyRequest("USD", null)).currencyCode()).isEqualTo("USD");
        // upsert 是端口唯一的写入方法：调用方无法表达"插入还是更新"，也就无法插出重复行
        verify(tenantConfigRepository).upsert(any(TenantConfig.class));
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
        verify(tenantConfigRepository, never()).upsert(any(TenantConfig.class));
        verify(storeRepository, never()).changeDefaultCurrency(any(), anyString());
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
        when(tenantConfigReader.findValue(TENANT_ID, 0L, "currency")).thenReturn(Optional.of("CNY"));
        when(storeRepository.changeDefaultCurrency(TENANT_ID, "USD")).thenReturn(2);
        when(walletAccountCurrencyPort.listNonZeroInOtherCurrency(TENANT_ID, "USD"))
                .thenReturn(List.of(wallet(7L, "CNY", 1500L, 0L)));
        when(walletAccountCurrencyPort.existsForSubject(TENANT_ID, 70L, 1L, "USD")).thenReturn(false);
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
        when(tenantConfigReader.findValue(TENANT_ID, 0L, "currency")).thenReturn(Optional.of("CNY"));
        when(walletAccountCurrencyPort.listNonZeroInOtherCurrency(TENANT_ID, "USD"))
                .thenReturn(List.of(wallet(7L, "CNY", 1500L, 0L)));
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
        TenantCurrencyController permissionController = new TenantCurrencyController(newService(), permissionClient);
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
        when(tenantConfigReader.findValue(TENANT_ID, 0L, "currency")).thenReturn(Optional.of("CNY"));
        when(walletAccountCurrencyPort.listNonZeroInOtherCurrency(TENANT_ID, "USD")).thenReturn(List.of(
                wallet(7L, "CNY", 1500L, 0L), wallet(8L, "CNY", 0L, 500L)));
        withContext("tenant.currency.manage");

        assertThatThrownBy(() -> controller.update(null, new UpdateCurrencyRequest("USD", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("2")
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(409);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("CURRENCY_SWITCH_BLOCKED_BY_BALANCE");
                });
        verify(tenantConfigRepository, never()).upsert(any(TenantConfig.class));
        verify(storeRepository, never()).changeDefaultCurrency(any(), anyString());
        verify(walletAccountCurrencyPort, never()).changeCurrency(any(), anyString());
    }

    /** §2.2.2 显式 migrateBalances=true：金额数字不变，只改币种并留痕。 */
    @Test
    void migratesWalletBalancesOnlyWhenExplicitlyRequested() {
        when(tenantConfigReader.findValue(TENANT_ID, 0L, "currency")).thenReturn(Optional.of("CNY"));
        when(storeRepository.changeDefaultCurrency(TENANT_ID, "USD")).thenReturn(0);
        WalletAccount account = wallet(7L, "CNY", 1500L, 0L);
        when(walletAccountCurrencyPort.listNonZeroInOtherCurrency(TENANT_ID, "USD"))
                .thenReturn(List.of(account));
        when(walletAccountCurrencyPort.existsForSubject(TENANT_ID, 70L, 1L, "USD")).thenReturn(false);
        withContext("tenant.currency.manage");

        controller.update(null, new UpdateCurrencyRequest("USD", true));

        // 只改币种：端口签名里没有金额参数，"顺手改金额"在编译期就无法表达
        verify(walletAccountCurrencyPort).changeCurrency(7L, "USD");
    }

    /** 同主体已有目标币种账户时跳过改写（禁止事实上的跨币种合并），并计入审计。 */
    @Test
    void skipsWalletMigrationWhenTargetCurrencyAccountAlreadyExists() {
        when(tenantConfigReader.findValue(TENANT_ID, 0L, "currency")).thenReturn(Optional.of("CNY"));
        when(storeRepository.changeDefaultCurrency(TENANT_ID, "USD")).thenReturn(0);
        when(walletAccountCurrencyPort.listNonZeroInOtherCurrency(TENANT_ID, "USD"))
                .thenReturn(List.of(wallet(7L, "CNY", 1500L, 0L)));
        when(walletAccountCurrencyPort.existsForSubject(TENANT_ID, 70L, 1L, "USD")).thenReturn(true);
        withContext("tenant.currency.manage");

        controller.update(null, new UpdateCurrencyRequest("USD", true));

        verify(walletAccountCurrencyPort, never()).changeCurrency(any(), anyString());
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
                newService(), new WalletTokenConfigApplicationService(tenantConfigReader));
        when(tenantConfigReader.findValue(200L, 0L, "currency")).thenReturn(Optional.empty());
        assertThat(internal.tenantCurrency(200L).currencyCode()).isEqualTo("USD");

        when(tenantConfigReader.findValue(200L, 0L, "currency")).thenReturn(Optional.of("CNY"));
        assertThat(internal.tenantCurrency(200L).currencyCode()).isEqualTo("CNY");
        assertThat(internal.tenantCurrency(200L).tenantId()).isEqualTo(200L);
    }

    private void withContext(String... permissions) {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, 1L, 1, List.of(permissions)));
    }

    private static WalletAccount wallet(Long id, String currency, Long available, Long frozen) {
        return new WalletAccount(id, TENANT_ID, id * 10, 1L, currency, available, frozen);
    }
}
