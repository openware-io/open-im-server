package io.openware.common.audit.infra.security;

import io.openware.infrastructure.security.CachedBodyHttpServletRequest;
import io.openware.infrastructure.security.InternalServiceAuthentication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 审计内部上报通道鉴权：{@code /internal/**} 必须携带鉴权版本 2 的内部服务 HMAC 签名。
 *
 * <p>复用 SDK 的 {@link InternalServiceAuthentication}（服务端校验实现）与内容哈希算法，
 * 只做「版本 + 来源白名单 + 时间窗 + 内容哈希 + 签名」五项校验，不额外引入 Redis 依赖
 * （审计写入按幂等键去重，重放不会产生重复留痕；多副本下的重放窗口已在遗留清单登记）。
 */
@Slf4j
public class InternalAuditAuthenticationFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PREFIX = "/internal/";
    /** 通过校验后写入请求属性的上报来源，控制器据此落 source_service。 */
    public static final String SOURCE_ATTRIBUTE = "audit.report.source";

    private final InternalServiceAuthentication authentication;

    public InternalAuditAuthenticationFilter(InternalServiceAuthentication authentication) {
        this.authentication = authentication;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String source = request.getHeader(InternalServiceAuthentication.SOURCE_HEADER);
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        if (!isValid(cachedRequest, source, cachedRequest.getCachedBody())) {
            log.warn("拒绝内部审计请求: method={}, path={}, source={}", request.getMethod(), request.getRequestURI(),
                    source);
            reject(response, "内部服务鉴权失败");
            return;
        }
        cachedRequest.setAttribute(SOURCE_ATTRIBUTE, source);
        chain.doFilter(cachedRequest, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PREFIX);
    }

    private boolean isValid(HttpServletRequest request, String source, byte[] body) {
        return InternalServiceAuthentication.AUTHENTICATION_VERSION.equals(
                request.getHeader(InternalServiceAuthentication.VERSION_HEADER))
                && isExpectedSource(source)
                && authentication.isValid(request.getMethod(), request.getRequestURI(), request.getQueryString(),
                    request.getContentType(), body, source,
                    request.getHeader(InternalServiceAuthentication.REQUEST_ID_HEADER),
                    request.getHeader(InternalServiceAuthentication.TIMESTAMP_HEADER),
                    request.getHeader(InternalServiceAuthentication.CONTENT_SHA256_HEADER),
                    request.getHeader(InternalServiceAuthentication.SIGNATURE_HEADER));
    }

    private boolean isExpectedSource(String source) {
        if (source == null) {
            return false;
        }
        return java.util.Arrays.stream(authentication.getExpectedSource().split(","))
                .map(String::trim)
                .anyMatch(source::equals);
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"INVALID_INTERNAL_SERVICE_AUTHENTICATION\",\"message\":\""
                + message + "\"}");
    }
}
