package io.openware.common.payment.config;

import lombok.extern.slf4j.Slf4j;

import io.openware.infrastructure.tenant.ClientAudienceHolder;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.common.payment.infra.client.TenantAuthorizationStateClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * B 端旧 token 失效校验：token 的 authorizationVersion 必须等于当前账号/门店快照版本。
 * 成员禁用、角色撤销后快照版本变化，旧 token 立即 401。
 */
@Slf4j
public class TenantAuthorizationStateFilter extends OncePerRequestFilter {

    private final TenantAuthorizationStateClient stateClient;

    public TenantAuthorizationStateFilter(TenantAuthorizationStateClient stateClient) {
        this.stateClient = stateClient;
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
            int current = stateClient.authorizationVersion(context.accountId(), context.tenantId(),
                    context.organizationId(), context.storeId());
            log.debug("authz check account={} app={} tokenVersion={} current={}", context.accountId(),
                    ClientAudienceHolder.get(), context.authorizationVersion(), current);
            if (current <= 0 || context.authorizationVersion() < current) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authorization version stale");
                return;
            }
        } catch (RuntimeException unavailable) {
            log.warn("authorization state unavailable account={} tenant={} store={}",
                    context.accountId(), context.tenantId(), context.storeId(), unavailable);
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Authorization state unavailable");
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
