package io.openware.platform.admin.infra.security;

import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.platform.admin.infra.client.AdminAuthorizationStateClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Base64;
import org.springframework.web.filter.OncePerRequestFilter;

/** admin 会话内 tenantContextToken 的授权版本校验；平台作用域跳过。 */
@Slf4j
public class AdminAuthorizationStateFilter extends OncePerRequestFilter {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AdminAuthorizationStateClient stateClient;

    public AdminAuthorizationStateFilter(AdminAuthorizationStateClient stateClient) {
        this.stateClient = stateClient;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/admin/auth/") || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        AdminContext context = AdminContextHolder.get();
        String token = context == null ? null : context.tenantContextToken();
        if (token == null || token.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        try {
            String payload = token.split("\\.")[1];
            JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(payload));
            if ("PLATFORM".equals(claims.path("scopeType").asText())) {
                chain.doFilter(request, response);
                return;
            }
            long tenantId = claims.path("tenantId").asLong();
            long accountId = Long.parseLong(claims.path("sub").asText());
            long storeId = claims.path("storeId").asLong();
            long organizationId = claims.path("organizationId").asLong();
            int tokenVersion = claims.path("authorizationVersion").asInt();
            int current = stateClient.authorizationVersion(accountId, tenantId, organizationId, storeId);
            if (current <= 0 || tokenVersion < current) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authorization version stale");
                return;
            }
        } catch (RuntimeException | IOException invalid) {
            log.warn("authorization state invalid for request {}", request.getRequestURI(), invalid);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authorization state invalid");
            return;
        }
        chain.doFilter(request, response);
    }
}
