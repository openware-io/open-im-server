package com.gvchat.platform.tenant.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.currency.Currency;
import com.gvchat.platform.tenant.infra.persistence.mapper.InternalTenantConfigMapper;
import com.gvchat.platform.tenant.infra.persistence.mapper.StoreMapper;
import com.gvchat.platform.tenant.infra.persistence.mapper.TenantConfigMapper;
import com.gvchat.platform.tenant.infra.persistence.mapper.WalletAccountCurrencyMapper;
import com.gvchat.platform.tenant.infra.persistence.po.StorePo;
import com.gvchat.platform.tenant.infra.persistence.po.TenantConfigPo;
import com.gvchat.platform.tenant.infra.persistence.po.WalletAccountCurrencyPo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 租户级币种单一来源（`docs/standards/16_CURRENCY_CONVENTIONS.md` §2）。
 *
 * <p>存储位置：`tnt_tenant_config` 的 `(tenant_id, store_id=0, config_key='currency')`。
 * 缺省（无行/空值/非法值）解析为 **USD**；非法值绝不抛给业务，只在下发写入时校验并 400。
 *
 * <p>切换币种必须显式处理 §2.2 的三条联动，不许静默：门店写穿、钱包余额保护、审计留痕
 * （支付方式能力校验在支付服务侧按当前上下文币种执行）。
 */
@Service
public class TenantCurrencyApplicationService {

    /** 租户级配置键：币种。 */
    public static final String CONFIG_KEY_CURRENCY = "currency";
    /** store_id=0 表示租户级（不分叉到门店）。 */
    private static final long TENANT_LEVEL_STORE_ID = 0L;

    private final TenantConfigMapper tenantConfigMapper;
    private final InternalTenantConfigMapper internalTenantConfigMapper;
    private final StoreMapper storeMapper;
    private final WalletAccountCurrencyMapper walletAccountMapper;

    public TenantCurrencyApplicationService(TenantConfigMapper tenantConfigMapper,
                                            InternalTenantConfigMapper internalTenantConfigMapper,
                                            StoreMapper storeMapper,
                                            WalletAccountCurrencyMapper walletAccountMapper) {
        this.tenantConfigMapper = tenantConfigMapper;
        this.internalTenantConfigMapper = internalTenantConfigMapper;
        this.storeMapper = storeMapper;
        this.walletAccountMapper = walletAccountMapper;
    }

    /**
     * 解析租户币种（读路径，跨租户只读，可无上下文调用——上下文签发路径依赖它）。
     * 无行/空值/非法值一律回退 {@link Currency#DEFAULT}（USD），绝不抛异常。
     */
    public Currency resolve(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            return Currency.DEFAULT;
        }
        return Currency.parse(internalTenantConfigMapper.selectTenantConfigValue(tenantId, CONFIG_KEY_CURRENCY));
    }

    /** 严格解析请求币种；不在支持列表内返回 400 CURRENCY_UNSUPPORTED。 */
    public static Currency requireSupported(String raw) {
        if (!Currency.isSupported(raw)) {
            throw new ApiException(400, "CURRENCY_UNSUPPORTED",
                    "不支持的币种: " + (raw == null ? "" : raw) + "，仅支持 CNY/USD");
        }
        return Currency.parse(raw);
    }

    /**
     * 切换租户币种（写路径）：校验 → 联动 → 落库，全部在同一事务内。
     *
     * @param tenantId         已按签名上下文收敛的租户
     * @param target           目标币种（已做 400 校验）
     * @param migrateBalances  §2.2.2：存在非零余额且币种不同的钱包账户时，缺省 409 阻断；
     *                         显式 true 才把这些账户币种改为目标币种（**金额数字不变**）
     * @return 变更前后值与受影响行数（供审计 detail 与响应）
     */
    @Transactional
    public CurrencySwitchResult switchCurrency(Long tenantId, Currency target, boolean migrateBalances) {
        Currency before = resolve(tenantId);

        // §2.2.2 钱包/储值余额保护：先算受影响账户，再决定阻断还是改写。
        List<WalletAccountCurrencyPo> affected = nonZeroAccountsInOtherCurrency(tenantId, target);
        if (!affected.isEmpty() && !migrateBalances) {
            throw new ApiException(409, "CURRENCY_SWITCH_BLOCKED_BY_BALANCE",
                    "存在 " + affected.size() + " 个非零余额且币种不同的钱包/储值账户，"
                            + "缺省不改写；确认按原金额数字改币种请显式传 migrateBalances=true");
        }
        int migrated = 0;
        int skipped = 0;
        for (WalletAccountCurrencyPo account : affected) {
            if (targetAccountExists(tenantId, account, target)) {
                // 同主体同目标币种账户已存在：改写会撞唯一键并造成事实上的合并（=跨币种折算，禁区），跳过并留痕。
                skipped++;
                continue;
            }
            WalletAccountCurrencyPo patch = new WalletAccountCurrencyPo();
            patch.setId(account.getId());
            patch.setCurrencyCode(target.code());
            patch.setUpdatedAt(LocalDateTime.now());
            walletAccountMapper.updateById(patch);
            migrated++;
        }

        // §2.2.1 门店写穿：同事务把所有门店 default_currency 改为目标币种，保证门店级界面与租户一致。
        int storesUpdated = writeThroughStores(tenantId, target);

        upsertTenantConfig(tenantId, target);

        return new CurrencySwitchResult(before, target, storesUpdated, migrated, skipped, affected.size());
    }

    private List<WalletAccountCurrencyPo> nonZeroAccountsInOtherCurrency(Long tenantId, Currency target) {
        return walletAccountMapper.selectList(new QueryWrapper<WalletAccountCurrencyPo>()
                .eq("tenant_id", tenantId)
                .ne("currency_code", target.code())
                .apply("(available_amount <> 0 OR frozen_amount <> 0)"));
    }

    private boolean targetAccountExists(Long tenantId, WalletAccountCurrencyPo account, Currency target) {
        QueryWrapper<WalletAccountCurrencyPo> query = new QueryWrapper<WalletAccountCurrencyPo>()
                .eq("tenant_id", tenantId)
                .eq("customer_id", account.getCustomerId())
                .eq("currency_code", target.code());
        // legal_entity_id 可为 null，eq(null) 在 SQL 里永远不成立，必须显式 IS NULL。
        if (account.getLegalEntityId() == null) {
            query.isNull("legal_entity_id");
        } else {
            query.eq("legal_entity_id", account.getLegalEntityId());
        }
        return walletAccountMapper.selectCount(query) > 0;
    }

    private int writeThroughStores(Long tenantId, Currency target) {
        List<StorePo> stores = storeMapper.selectList(new QueryWrapper<StorePo>().eq("tenant_id", tenantId));
        List<Long> mismatched = stores.stream()
                .filter(store -> !target.code().equals(store.getDefaultCurrency()))
                .map(StorePo::getId)
                .toList();
        if (mismatched.isEmpty()) {
            return 0;
        }
        StorePo patch = new StorePo();
        patch.setDefaultCurrency(target.code());
        patch.setUpdatedAt(LocalDateTime.now());
        storeMapper.update(patch, new QueryWrapper<StorePo>().in("id", mismatched));
        return mismatched.size();
    }

    private void upsertTenantConfig(Long tenantId, Currency target) {
        List<TenantConfigPo> rows = tenantConfigMapper.selectList(new QueryWrapper<TenantConfigPo>()
                .eq("tenant_id", tenantId).eq("store_id", TENANT_LEVEL_STORE_ID).eq("config_key", CONFIG_KEY_CURRENCY));
        if (!rows.isEmpty()) {
            TenantConfigPo po = rows.get(0);
            po.setConfigValue(target.code());
            po.setUpdatedAt(LocalDateTime.now());
            tenantConfigMapper.updateById(po);
            return;
        }
        TenantConfigPo po = new TenantConfigPo();
        po.setTenantId(tenantId);
        po.setStoreId(TENANT_LEVEL_STORE_ID);
        po.setConfigKey(CONFIG_KEY_CURRENCY);
        po.setConfigValue(target.code());
        po.setStatus("ACTIVE");
        po.setVersion(0);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        tenantConfigMapper.insert(po);
    }

    /**
     * 币种切换结果。
     *
     * @param before            变更前币种（缺省 USD）
     * @param after             变更后币种
     * @param storesUpdated     §2.2.1 写穿的门店数
     * @param walletsMigrated   §2.2.2 显式 migrateBalances 时改写的钱包账户数
     * @param walletsSkipped    §2.2.2 目标币种同主体账户已存在而跳过改写的账户数
     * @param walletsAffected   非零余额且币种不同的账户总数
     */
    public record CurrencySwitchResult(Currency before, Currency after, int storesUpdated,
                                       int walletsMigrated, int walletsSkipped, int walletsAffected) {
    }
}
