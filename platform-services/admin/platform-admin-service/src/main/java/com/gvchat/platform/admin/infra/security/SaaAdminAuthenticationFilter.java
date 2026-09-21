package com.gvchat.platform.admin.infra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** SaaS 后台会话鉴权过滤器：校验 Redis 会话（HttpOnly Cookie），命中则写入 AdminContextHolder，否则 401。 */
@Slf4j
public class SaaAdminAuthenticationFilter extends OncePerRequestFilter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SaaAdminSessionStore sessionStore;

    public SaaAdminAuthenticationFilter(SaaAdminSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/admin/auth/login")
            || path.equals("/admin/auth/sso")
            || path.equals("/admin/auth/session")
            || path.equals("/admin/auth/csrf")
            || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sessionId = resolveSessionId(request);
        if (sessionId == null) {
            writeError(response, 401, "ADMIN_SESSION_MISSING", "missing SaaS admin session");
            return;
        }
        Optional<AdminContext> context = sessionStore.findSession(sessionId);
        if (context.isEmpty()) {
            log.warn("SaaS admin session rejected: method={}, path={}", request.getMethod(), request.getRequestURI());
            writeError(response, 401, "ADMIN_SESSION_INVALID", "invalid or expired SaaS admin session");
            return;
        }
        if (requiresCsrfValidation(request) && !sessionStore.isCsrfTokenValid(sessionId, request.getHeader("X-CSRF-Token"))) {
            writeError(response, 403, "CSRF_TOKEN_INVALID", "missing or invalid CSRF token");
            return;
        }
        AdminContextHolder.set(context.get());
        try {
            chain.doFilter(request, response);
        } finally {
            AdminContextHolder.clear();
        }
    }

    private static String resolveSessionId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AdminSessionCookie.NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static boolean requiresCsrfValidation(HttpServletRequest request) {
        String method = request.getMethod();
        return !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)
            && !"OPTIONS".equalsIgnoreCase(method);
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        OBJECT_MAPPER.writeValue(response.getWriter(), body);
    }
}
