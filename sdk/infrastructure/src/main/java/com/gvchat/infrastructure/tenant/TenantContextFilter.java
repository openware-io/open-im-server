package com.gvchat.infrastructure.tenant;

import com.gvchat.infrastructure.currency.Currency;
import com.gvchat.infrastructure.currency.CurrencyContextHolder;
import com.gvchat.infrastructure.security.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;

/**
 * 租户上下文过滤器：仅接受 identity service 签发的短期 JWT 上下文。
 * 客户端构造的 JSON 头不具备签名，不能越过租户或账号边界。
 */
public class TenantContextFilter extends OncePerRequestFilter {
    private static final String ISSUER = "gv-saas-identity";
    private static final String AUDIENCE = "gv-saas-services";
    private final SecretKey secretKey;

    public TenantContextFilter(JwtProperties jwtProperties) {
        jwtProperties.validate();
        secretKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader(TenantContext.HEADER);
            if (header != null && !header.isBlank()) {
                Claims claims = verify(header);
                TenantContextHolder.set(toContext(claims), header);
                // 币种与租户上下文同生共死，但走旁路 holder：不牵动 TenantContext 规范构造器。
                // claim 缺失（老 token）或取值非法一律回退 USD，绝不因此 401。
                CurrencyContextHolder.set(Currency.parse(claims.get("currency", String.class)));
            }
            chain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException exception) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid tenant context");
        } finally {
            TenantContextHolder.clear();
            CurrencyContextHolder.clear();
        }
    }

    /** 验签并取 payload；签名/签发方/受众任一不符即抛异常，由调用方转 401。 */
    private Claims verify(String token) {
        return Jwts.parser().verifyWith(secretKey).requireIssuer(ISSUER).requireAudience(AUDIENCE)
                .build().parseSignedClaims(token).getPayload();
    }

    private static TenantContext toContext(Claims claims) {
        long accountId = Long.parseLong(claims.getSubject());
        if (accountId <= 0) {
            throw new IllegalArgumentException("invalid accountId");
        }
        String scopeType = normalizeScope(claims.get("scopeType", String.class));
        Number tenantClaim = claims.get("tenantId", Number.class);
        long tenantId = tenantClaim == null ? 0L : tenantClaim.longValue();
        // 平台运营上下文允许「无租户约束」（tenantId=0，平台级动作如创建租户）；
        // 非平台作用域仍必须是正数租户，客户端无法自行声明 scopeType（它由签名 token 携带）。
        if (tenantId <= 0 && !TenantContext.SCOPE_PLATFORM.equals(scopeType)) {
            throw new IllegalArgumentException("invalid tenantId");
        }
        return new TenantContext(tenantId, nullableLong(claims, "organizationId"), nullableLong(claims, "storeId"),
                accountId, claims.get("authorizationVersion", Integer.class),
                claims.get("permissions", List.class) == null ? List.of() : claims.get("permissions", List.class),
                scopeType);
    }

    /** 作用域声明归一：只认 {@code PLATFORM}（大小写与空白容忍），其余一律按无作用域处理。 */
    private static String normalizeScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return TenantContext.SCOPE_PLATFORM.equals(value) ? value : null;
    }

    private static Long nullableLong(Claims claims, String name) {
        Number value = claims.get(name, Number.class);
        return value == null ? null : value.longValue();
    }
}
