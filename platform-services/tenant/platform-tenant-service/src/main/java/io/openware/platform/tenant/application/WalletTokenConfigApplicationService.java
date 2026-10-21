package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.domain.configuration.model.TenantConfig;
import io.openware.platform.tenant.domain.configuration.repository.TenantConfigReader;
import io.openware.platform.tenant.domain.configuration.repository.TenantConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

/**
 * 租户级钱包代币展示配置（品牌展示名 + 兑换比例）的唯一读取口径。
 *
 * <p>存储位置：{@code tnt_tenant_config} 的 {@code (tenant_id, store_id=0)} 下的键
 * {@code wallet_brand_name} / {@code wallet_ratio}（见 {@code docs/80-development/standards/24_CURRENCY_CONVENTIONS.md} §9）。
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
@Slf4j
public class WalletTokenConfigApplicationService {

    /** 租户配置键：代币品牌展示名（非币种）。 */
    public static final String CONFIG_KEY_BRAND_NAME = "wallet_brand_name";
    /** 租户配置键：1 个主单位兑换的代币个数。 */
    public static final String CONFIG_KEY_RATIO = "wallet_ratio";
    /** 缺省品牌展示名（与租户配置种子值一致，可用 SaaS 后台覆盖）。 */
    public static final String DEFAULT_BRAND_NAME = "OpenChat币";
    /** 缺省比例：1 个主单位 = 100 个代币。 */
    public static final long DEFAULT_RATIO = 100L;

    private final TenantConfigReader tenantConfigReader;
    private final TenantConfigRepository tenantConfigRepository;
    private final AuditClient auditClient;

    /** 兼容只读路径的既有装配/单测：写端口与审计客户端按关闭态处理。 */
    public WalletTokenConfigApplicationService(TenantConfigReader tenantConfigReader) {
        this(tenantConfigReader, null, AuditClient.disabled());
    }

    @Autowired
    public WalletTokenConfigApplicationService(TenantConfigReader tenantConfigReader,
                                               TenantConfigRepository tenantConfigRepository,
                                               AuditClient auditClient) {
        this.tenantConfigReader = tenantConfigReader;
        this.tenantConfigRepository = tenantConfigRepository;
        this.auditClient = auditClient;
    }

    /**
     * 修改租户代币展示配置：权限、租户边界、两个键的写入与审计都在应用服务完成。
     *
     * <p>控制器只做协议转换；租户一律取自签名上下文，请求里的 tenantId 只能与上下文一致。
     */
    @Transactional
    public TenantWalletTokenConfig update(Long requestedTenantId, String brandName, long ratio) {
        try {
            PermissionGuard.require("tenant.tenant.manage");
            Long tenantId = requireScopedTenant(requestedTenantId);
            if (tenantConfigRepository == null) {
                throw new IllegalStateException("tenant config write port is not configured");
            }
            tenantConfigRepository.upsert(TenantConfig.tenantLevel(CONFIG_KEY_BRAND_NAME, tenantId, brandName));
            tenantConfigRepository.upsert(TenantConfig.tenantLevel(CONFIG_KEY_RATIO, tenantId, String.valueOf(ratio)));
            // 租户级配置改动影响全租户账单/充值展示：留痕并记录变更前后值摘要。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(tenantId)
                    .action("tenant.config.update")
                    .resourceType("tnt_tenant_config").resourceId(String.valueOf(tenantId))
                    .resourceName(brandName)
                    .detailJson("{\"brandName\":\"" + brandName + "\",\"ratio\":" + ratio + "}")
                    .build());
            return new TenantWalletTokenConfig(brandName, ratio);
        } catch (RuntimeException failure) {
            // 失败出口留痕（缺权限/越权租户/参数非法/落库失败）：审计只 WARN，业务异常原样抛出。
            recordFailure(failure, requestedTenantId);
            throw failure;
        }
    }

    /** 租户边界：请求里的 tenantId 只能与上下文一致（缺省时直接用上下文租户）。 */
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

    /**
     * 失败留痕：租户级配置写操作的失败出口此前完全没有留痕。
     * detail 只放可检索的租户 ID，配置内容（品牌名）不重复进失败详情。
     */
    private void recordFailure(RuntimeException failure, Long requestedTenantId) {
        TenantContext context = TenantContextHolder.get();
        Long tenantId = requestedTenantId != null ? requestedTenantId
                : (context == null ? null : context.tenantId());
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(tenantId)
                .action("tenant.config.update")
                .resourceType("tnt_tenant_config")
                .resourceId(tenantId == null ? null : String.valueOf(tenantId))
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .build());
    }

    /** 管理端读取：按签名上下文做租户边界校验后再解析。 */
    public TenantWalletTokenConfig resolveScoped(Long requestedTenantId) {
        return resolve(requireScopedTenant(requestedTenantId));
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
                tenantConfigReader.findValue(tenantId, 0L, CONFIG_KEY_BRAND_NAME).orElse(null));
        return new TenantWalletTokenConfig(brandName, parseRatio(
                tenantConfigReader.findValue(tenantId, 0L, CONFIG_KEY_RATIO).orElse(null)));
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
            log.debug("wallet token ratio could not be read, using the configured default", invalid);
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
