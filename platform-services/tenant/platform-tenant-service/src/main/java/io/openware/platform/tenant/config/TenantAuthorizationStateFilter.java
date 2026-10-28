package io.openware.platform.tenant.config;

import lombok.extern.slf4j.Slf4j;

import io.openware.infrastructure.tenant.ClientAudienceHolder;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.application.PermissionSnapshotProvider;
import io.openware.platform.tenant.domain.authorization.PermissionSnapshot;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** tenant 管理面旧 token 失效：token 授权版本必须等于当前快照版本。 */
@Slf4j
public class TenantAuthorizationStateFilter extends OncePerRequestFilter {

    private final PermissionSnapshotProvider provider;

    public TenantAuthorizationStateFilter(PermissionSnapshotProvider provider) {
        this.provider = provider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.platformScope() || context.tenantId() <= 0
                || !isBackoffice(ClientAudienceHolder.get())) {
            chain.doFilter(request, response);
            return;
        }
        try {
            PermissionSnapshot snapshot = provider.snapshot(context.accountId(), context.tenantId(),
                    context.organizationId(), context.storeId(), true);
            int current = snapshot == null ? 0 : snapshot.authorizationVersion();
            if (current <= 0 || context.authorizationVersion() < current) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authorization version stale");
                return;
            }
        } catch (RuntimeException exception) {
            log.warn("authorization state invalid for request {}", request.getRequestURI(), exception);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authorization state invalid");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isBackoffice(String clientApp) {
        if (clientApp == null || clientApp.isBlank()) {
            return false;
        }
        return "saas-b".equals(clientApp) || clientApp.contains("-h5") || clientApp.endsWith("-b");
    }
}
