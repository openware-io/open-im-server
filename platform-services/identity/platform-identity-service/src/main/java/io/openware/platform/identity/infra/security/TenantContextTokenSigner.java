package io.openware.platform.identity.infra.security;

import io.openware.infrastructure.currency.Currency;
import io.openware.infrastructure.security.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * 经营上下文 Token 签名器：复用现有 jwt.secret 签发 30 分钟短期 JWT，
 * payload 含 tenantId / organizationId / storeId / authorizationVersion / permissions / currency。
 */
@Component
public class TenantContextTokenSigner {

    /** 上下文 Token 有效期（30 分钟）。 */
    private static final long TTL_MS = 30 * 60 * 1000L;

    private final SecretKey secretKey;

    public TenantContextTokenSigner(JwtProperties jwtProperties) {
        jwtProperties.validate();
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发经营上下文 Token。
     *
     * @param accountId             账号 ID（写入 subject）
     * @param tenantId              租户 ID
     * @param organizationId        组织 ID（可为 null）
     * @param storeId               门店 ID（可为 null）
     * @param authorizationVersion  授权版本（取该角色分配版本）
     * @param permissions           权限码列表
     * @return JWT 字符串
     */
    public String sign(Long accountId, Long tenantId, Long organizationId, Long storeId,
                       int authorizationVersion, List<String> permissions) {
        return sign(accountId, tenantId, organizationId, storeId, authorizationVersion, permissions, null);
    }

    /**
     * 签发经营上下文 Token，并写入币种 claim {@code currency}（规范 §3.1）。
     *
     * <p>claim 值只可能是受支持币种代码；缺失/非法一律 USD（与「租户未配置即 USD」一致）。
     * 下游 {@code TenantContextFilter} 解析该 claim，老 token 无该 claim 时同样回退 USD、不得 401。
     *
     * @param currencyCode 租户币种（可为 null → USD）
     */
    public String sign(Long accountId, Long tenantId, Long organizationId, Long storeId,
                       int authorizationVersion, List<String> permissions, String currencyCode) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + TTL_MS);
        return Jwts.builder()
                .subject(String.valueOf(accountId))
                .issuer("open-saas-identity")
                .audience().add("open-saas-services").and()
                .claim("tenantId", tenantId)
                .claim("organizationId", organizationId)
                .claim("storeId", storeId)
                .claim("authorizationVersion", authorizationVersion)
                .claim("permissions", permissions)
                .claim("currency", Currency.parse(currencyCode).code())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }
}
