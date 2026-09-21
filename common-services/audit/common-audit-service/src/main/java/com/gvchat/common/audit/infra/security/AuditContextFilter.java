package com.gvchat.common.audit.infra.security;

import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 审计租户/平台上下文过滤器：只信任 identity/admin BFF 用共享密钥签发的短期 JWT。
 *
 * <p>与 SDK {@code TenantContextFilter} 的差异（有意为之）：
 * <ul>
 *   <li>允许上下文**没有租户约束**（{@code tenantId} 缺失或 {@code <=0}）——平台视角的全量审计查询
 *       正是这种上下文；SDK 过滤器会把这种请求直接 401，无法表达平台视角；</li>
 *   <li>额外读取 {@code scopeType} 声明（PLATFORM/TENANT/STORE），写入 {@link AuditCallerScopeHolder}，
 *       租户与平台的分层判定不再依赖客户端参数；</li>
 *   <li>缺少 {@code JWT_SECRET} 时**拒绝**而不是信任明文头：无法验签的上下文一律 503 并留 ERROR 日志。</li>
 * </ul>
 */
@Slf4j
public class AuditContextFilter extends OncePerRequestFilter {

    private static final String ISSUER = "gv-saas-identity";
    private static final String AUDIENCE = "gv-saas-services";

    private final SecretKey secretKey;

    public AuditContextFilter(String jwtSecret) {
        this.secretKey = jwtSecret == null || jwtSecret.isBlank()
                ? null
                : Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(TenantContext.HEADER);
        if (header == null || header.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        if (secretKey == null) {
            log.error("审计服务未配置 JWT_SECRET，无法校验租户上下文，拒绝请求: path={}", request.getRequestURI());
            reject(response, 503, "AUDIT_CONTEXT_UNVERIFIABLE", "审计服务缺少 JWT_SECRET，无法校验运营上下文");
            return;
        }
        try {
            apply(header);
        } catch (JwtException | IllegalArgumentException exception) {
            log.warn("非法租户上下文被拒绝: path={}, cause={}", request.getRequestURI(), exception.getMessage());
            reject(response, 401, "INVALID_TENANT_CONTEXT", "运营上下文无效或已过期");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
            AuditCallerScopeHolder.clear();
        }
    }

    private void apply(String token) {
        Claims claims = Jwts.parser().verifyWith(secretKey).requireIssuer(ISSUER).requireAudience(AUDIENCE)
                .build().parseSignedClaims(token).getPayload();
        long accountId = Long.parseLong(claims.getSubject());
        if (accountId <= 0) {
            throw new IllegalArgumentException("invalid accountId");
        }
        Number tenantClaim = claims.get("tenantId", Number.class);
        long tenantId = tenantClaim == null ? 0L : Math.max(tenantClaim.longValue(), 0L);
        Number authorizationVersion = claims.get("authorizationVersion", Number.class);
        List<?> rawPermissions = claims.get("permissions", List.class);
        List<String> permissions = rawPermissions == null
                ? List.of()
                : rawPermissions.stream().map(String::valueOf).toList();
        TenantContextHolder.set(new TenantContext(tenantId, nullableLong(claims, "organizationId"),
                nullableLong(claims, "storeId"), accountId,
                authorizationVersion == null ? 0 : authorizationVersion.intValue(), permissions), token);
        AuditCallerScopeHolder.set(new AuditCallerScope(claims.get("scopeType", String.class), tenantId));
    }

    private static Long nullableLong(Claims claims, String name) {
        Number value = claims.get(name, Number.class);
        return value == null ? null : value.longValue();
    }

    private static void reject(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
