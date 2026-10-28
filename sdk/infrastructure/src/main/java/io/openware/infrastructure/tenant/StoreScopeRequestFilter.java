package io.openware.infrastructure.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 门店数据域请求过滤器：门店上下文下强制把 storeId 收敛到当前门店。
 *
 * <p>为什么放在 Servlet 层：报表、订单、资源以及后续按摩/桌球等业务都会各自声明
 * {@code @RequestParam(required = false) Long storeId}。如果每个控制器都靠开发者记得
 * 补门店筛选，新增业务就很容易再次把租户下其它门店数据带出去。这里统一做三件事：
 * <ol>
 *   <li>请求带了别的 storeId → 403，不允许跨店查询；</li>
 *   <li>请求没带 storeId → 包装请求并注入当前 storeId，控制器自然收到当前门店；</li>
 *   <li>storeIds 多店参数 → 门店上下文只允许当前门店，其它值 403。</li>
 * </ol>
 *
 * <p>该过滤器不是角色权限校验；角色权限仍由各接口的 {@code PermissionGuard} 执行。
 * 两层共同构成“功能权限 + 数据域权限”，缺一不可。
 */
public class StoreScopeRequestFilter extends OncePerRequestFilter {

    public static final String STORE_ID = "storeId";
    public static final String STORE_IDS = "storeIds";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/internal") || path.startsWith("/admin/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.storeId() == null || context.storeId() <= 0) {
            chain.doFilter(request, response);
            return;
        }
        long currentStoreId = context.storeId();
        String requestedStore = request.getParameter(STORE_ID);
        if (requestedStore != null && !requestedStore.isBlank()
                && !String.valueOf(currentStoreId).equals(requestedStore.trim())) {
            writeForbidden(response);
            return;
        }
        String requestedStores = request.getParameter(STORE_IDS);
        if (requestedStores != null && !requestedStores.isBlank()) {
            for (String part : requestedStores.split(",")) {
                if (!part.isBlank() && !String.valueOf(currentStoreId).equals(part.trim())) {
                    writeForbidden(response);
                    return;
                }
            }
        }
        chain.doFilter(new StoreScopeRequest(request, currentStoreId), response);
    }

    private static void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"STORE_SCOPE_FORBIDDEN\",\"message\":\"无权访问其它门店数据\"}");
    }

    /** 只补充/覆盖门店参数，不改变原有查询参数顺序和值。 */
    private static final class StoreScopeRequest extends HttpServletRequestWrapper {
        private final Map<String, String[]> parameters;

        private StoreScopeRequest(HttpServletRequest request, long currentStoreId) {
            super(request);
            Map<String, String[]> merged = new LinkedHashMap<>(request.getParameterMap());
            merged.put(STORE_ID, new String[]{String.valueOf(currentStoreId)});
            String stores = request.getParameter(STORE_IDS);
            if (stores == null || stores.isBlank()) {
                merged.put(STORE_IDS, new String[]{String.valueOf(currentStoreId)});
            }
            parameters = Collections.unmodifiableMap(merged);
        }

        @Override
        public String getParameter(String name) {
            String[] values = parameters.get(name);
            return values == null || values.length == 0 ? null : values[0];
        }

        @Override
        public String[] getParameterValues(String name) {
            return parameters.get(name);
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return parameters;
        }
    }
}
