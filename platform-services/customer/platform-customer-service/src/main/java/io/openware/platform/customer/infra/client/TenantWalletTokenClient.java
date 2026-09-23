package io.openware.platform.customer.infra.client;

import io.openware.infrastructure.security.InternalServiceAuthentication;
import io.openware.infrastructure.security.InternalServiceSignature;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * tenant-service 内部端点客户端：读取「目标租户」的钱包代币展示配置（品牌名 + ratio）。
 *
 * <p>调用 {@code GET /internal/iam/tenant-config/{tenantId}/wallet-token}，与 identity/admin 读取租户币种
 * 走同一条内部通道（{@code /internal/iam/**} 由 tenant-service 的
 * {@code InternalServiceAuthenticationFilter} 统一校验 HMAC 鉴权版本 2；客户端无法经网关访问
 * {@code /internal/**}）。出站签名与服务端校验共用 {@link InternalServiceSignature}，避免两侧口径漂移。
 *
 * <p><b>失败一律回退缺省并只打 WARN</b>：租户上下文缺失、网络/超时、401（source 不在
 * {@code internal.service-auth.expected-source} 白名单）、响应为空、品牌/比例非法——都退化成
 * {@code A380币 / 100}。C 端余额与流水展示不能因为租户配置读取失败而失败，也绝不把代币数量当金额。
 *
 * <p>本客户端只取「品牌展示名 + 比例」两个展示用值：<b>不得</b>用于入账 / 扣减 / 对账 / 退款 / 日结。
 */
@Slf4j
@Component
public class TenantWalletTokenClient {

    /** 内部端点路径模板（tenantId 为纯数字，直接内插不产生路径注入）。 */
    private static final String WALLET_TOKEN_PATH = "/internal/iam/tenant-config/%d/wallet-token";

    /** 缺省代币品牌展示名（与 tenant-service 的 wallet_brand_name 缺省一致；是文案，不是币种）。 */
    public static final String DEFAULT_BRAND_NAME = "A380币";
    /** 缺省比例：1 个主单位 = 100 个代币。 */
    public static final long DEFAULT_RATIO = 100L;

    private final RestClient restClient;
    private final String serviceName;
    private final String secret;

    public TenantWalletTokenClient(
            @Value("${app.tenant-service.base-url:http://localhost:4110}") String baseUrl,
            @Value("${internal.service-auth.service-name:platform-customer-service}") String serviceName,
            @Value("${internal.service-auth.secret:}") String secret) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.serviceName = serviceName;
        this.secret = secret;
    }

    /**
     * 目标租户的代币展示配置；任何失败（含 tenantId 为空/非正、密钥未注入、租户服务不可达）一律回退缺省，
     * <b>绝不抛出</b>。
     */
    public WalletTokenConfig resolve(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            return WalletTokenConfig.DEFAULT;
        }
        String path = String.format(WALLET_TOKEN_PATH, tenantId);
        try {
            TenantWalletToken token = get(path);
            if (token == null) {
                return WalletTokenConfig.DEFAULT;
            }
            return new WalletTokenConfig(brandNameOf(token.brandName()), ratioOf(token.ratio()));
        } catch (RuntimeException failure) {
            log.warn("读取租户代币展示配置失败，回退缺省品牌 {}/比例 {}: tenantId={}, reason={}",
                    DEFAULT_BRAND_NAME, DEFAULT_RATIO, tenantId, failure.toString());
            return WalletTokenConfig.DEFAULT;
        }
    }

    private TenantWalletToken get(String path) {
        long timestamp = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String contentHash = InternalServiceSignature.contentHash(new byte[0]);
        String signature = InternalServiceSignature.sign(secret, "GET", path, null, null, contentHash,
                serviceName, requestId, timestamp);
        return restClient.get()
                .uri(path)
                .header(InternalServiceAuthentication.SOURCE_HEADER, serviceName)
                .header(InternalServiceAuthentication.VERSION_HEADER,
                        InternalServiceAuthentication.AUTHENTICATION_VERSION)
                .header(InternalServiceAuthentication.REQUEST_ID_HEADER, requestId)
                .header(InternalServiceAuthentication.CONTENT_SHA256_HEADER, contentHash)
                .header(InternalServiceAuthentication.TIMESTAMP_HEADER, Long.toString(timestamp))
                .header(InternalServiceAuthentication.SIGNATURE_HEADER, signature)
                .retrieve()
                .body(TenantWalletToken.class);
    }

    private static String brandNameOf(String raw) {
        return raw == null || raw.isBlank() ? DEFAULT_BRAND_NAME : raw.trim();
    }

    private static long ratioOf(Long raw) {
        return raw == null || raw <= 0 ? DEFAULT_RATIO : raw;
    }

    /** tenant-service 内部契约：钱包代币展示配置（字段名与 InternalTenantWalletToken 一致）。 */
    public record TenantWalletToken(Long tenantId, String brandName, Long ratio) {
    }

    /**
     * 归一化后的代币展示配置：品牌名保证非空白、ratio 保证为正（缺省 {@code A380币 / 100}）。
     * <b>只用于展示代币数量</b>，禁止参与任何金额计算。
     */
    public record WalletTokenConfig(String brandName, long ratio) {
        /** 缺省配置（无租户上下文 / 调用失败 / 非法响应）。 */
        public static final WalletTokenConfig DEFAULT = new WalletTokenConfig(DEFAULT_BRAND_NAME, DEFAULT_RATIO);
    }
}
