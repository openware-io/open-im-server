package com.gvchat.platform.tenant.application;

import com.gvchat.platform.tenant.infra.persistence.mapper.InternalTenantConfigMapper;
import org.springframework.stereotype.Service;

/**
 * 租户级钱包代币展示配置（品牌展示名 + 兑换比例）的唯一读取口径。
 *
 * <p>存储位置：{@code tnt_tenant_config} 的 {@code (tenant_id, store_id=0)} 下的键
 * {@code wallet_brand_name} / {@code wallet_ratio}（见 {@code docs/standards/16_CURRENCY_CONVENTIONS.md} §9）。
 * 缺省为 {@code A380币} / {@code 100}；无行、空值、非数字、非正数一律按缺省处理，<b>读路径绝不抛异常</b>——
 * 租户配置读取失败不能影响 C 端余额/流水展示，也不能影响客户端渲染。
 *
 * <p><b>ratio 语义（冻结）</b>：{@code ratio = N} 表示 <b>1 个主单位（1 元 / 1 美元，随租户币种，默认 USD）
 * = N 个代币</b>。该比例<b>只用于展示代币数量</b>（{@code 代币数量 = 余额最小单位 ÷ 100 × ratio}），
 * <b>禁止参与任何入账 / 扣减 / 对账 / 退款 / 日结计算</b>——钱包账本一律只用最小货币单位的整数金额。
 *
 * <p>跨租户只读：调用方必须显式传入 tenantId；本服务不做租户上下文校验（内部端点与已收敛租户的
 * 管理端读路径各自负责边界），只暴露只读求值。
 */
@Service
public class WalletTokenConfigApplicationService {

    /** 租户配置键：代币品牌展示名（非币种）。 */
    public static final String CONFIG_KEY_BRAND_NAME = "wallet_brand_name";
    /** 租户配置键：1 个主单位兑换的代币个数。 */
    public static final String CONFIG_KEY_RATIO = "wallet_ratio";
    /** 缺省品牌展示名（与 V7__seed_a380_tenant.sql 的种子值一致）。 */
    public static final String DEFAULT_BRAND_NAME = "A380币";
    /** 缺省比例：1 个主单位 = 100 个代币。 */
    public static final long DEFAULT_RATIO = 100L;

    private final InternalTenantConfigMapper internalTenantConfigMapper;

    public WalletTokenConfigApplicationService(InternalTenantConfigMapper internalTenantConfigMapper) {
        this.internalTenantConfigMapper = internalTenantConfigMapper;
    }

    /**
     * 解析目标租户的代币展示配置。
     *
     * <p>tenantId 为空/非正、配置缺行、值为空或非法（非数字、&le;0）一律回退
     * {@code A380币 / 100}，绝不抛异常。
     */
    public TenantWalletTokenConfig resolve(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            return TenantWalletTokenConfig.DEFAULT;
        }
        String brandName = normalizeBrandName(
                internalTenantConfigMapper.selectTenantConfigValue(tenantId, CONFIG_KEY_BRAND_NAME));
        return new TenantWalletTokenConfig(brandName, parseRatio(
                internalTenantConfigMapper.selectTenantConfigValue(tenantId, CONFIG_KEY_RATIO)));
    }

    /** 品牌展示名归一化：空值/空白回退缺省名；去首尾空白。品牌名是展示文案，不是币种。 */
    public static String normalizeBrandName(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_BRAND_NAME;
        }
        return raw.trim();
    }

    /** 比例归一化：空值/非数字/非正数一律回退缺省 100；读路径不抛错。 */
    public static long parseRatio(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_RATIO;
        }
        try {
            long ratio = Long.parseLong(raw.trim());
            return ratio > 0 ? ratio : DEFAULT_RATIO;
        } catch (NumberFormatException invalid) {
            return DEFAULT_RATIO;
        }
    }

    /**
     * 租户代币展示配置：品牌展示名 + 1 个主单位兑换的代币个数。
     * <b>仅展示口径</b>，任何金额计算都不得使用这里的 ratio。
     */
    public record TenantWalletTokenConfig(String brandName, long ratio) {
        /** 缺省配置（无租户上下文 / 无配置行 / 非法值）。 */
        public static final TenantWalletTokenConfig DEFAULT =
                new TenantWalletTokenConfig(DEFAULT_BRAND_NAME, DEFAULT_RATIO);
    }
}
