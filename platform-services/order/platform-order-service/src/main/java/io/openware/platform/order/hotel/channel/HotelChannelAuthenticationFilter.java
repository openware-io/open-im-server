package io.openware.platform.order.hotel.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.infrastructure.observability.RequestIdContext;
import io.openware.infrastructure.security.CachedBodyHttpServletRequest;
import io.openware.infrastructure.tenant.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 酒店 OTA channel v1 的入站鉴权过滤器。
 *
 * <p>只拦截 {@code /api/v1/open/hotel/**}：校验 channel code、时间戳窗口与 HMAC-SHA256 签名，
 * 失败一律 401 {@code CHANNEL_UNAUTHORIZED}，不存在「签名失败仍放行」的开关。校验通过后把
 * 请求体包装成可重复读取，并写入只锁定租户的临时上下文；门店/组织由下游应用服务按酒店与门店
 * 权威数据解析（见 {@link HotelChannelBookingApplicationService}）。
 *
 * <p>重放：v1 的写接口天然幂等（创建按 externalRef 回放、取消按最终状态回放），因此不做 nonce 缓存，
 * 只保留时间戳窗口抵御无限期重放。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@Slf4j
public class HotelChannelAuthenticationFilter extends OncePerRequestFilter {

    /** 外部 OTA 入口前缀（网关不剥前缀，下游与外部路径一致）。 */
    public static final String PATH_PREFIX = "/api/v1/open/hotel/";

    /** 鉴权通过后写入 request attribute 的渠道编码。 */
    public static final String CHANNEL_ATTRIBUTE =
            HotelChannelAuthenticationFilter.class.getName() + ".channelCode";

    private final HotelChannelProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HotelChannelAuthenticationFilter(HotelChannelProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String channelCode = request.getHeader(HotelChannelSignature.CHANNEL_CODE_HEADER);
        HotelChannelProperties.Channel channel = properties.find(channelCode).orElse(null);
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        String reason = channel == null ? "CHANNEL_UNKNOWN_OR_UNCONFIGURED"
                : authenticate(cachedRequest, channel, channelCode);
        if (channel == null || reason != null) {
            log.warn("rejected hotel channel request: method={}, path={}, channel={}, reason={}",
                    request.getMethod(), request.getRequestURI(), channelCode, reason);
            reject(response);
            return;
        }
        TenantContextHolder.set(HotelChannelContexts.provisional(channel.getTenantId()));
        cachedRequest.setAttribute(CHANNEL_ATTRIBUTE, channelCode.trim());
        try {
            chain.doFilter(cachedRequest, response);
        } finally {
            // 兜底清理：上下文是适配器自己写入的，不能依赖入口过滤器（/open 不带上下文头）。
            TenantContextHolder.clear();
        }
    }

    /** 返回 null 表示通过；否则返回用于日志的拒绝原因（不回显给调用方细节）。 */
    private String authenticate(CachedBodyHttpServletRequest request, HotelChannelProperties.Channel channel,
                                String channelCode) {
        long timestamp;
        try {
            timestamp = Long.parseLong(request.getHeader(HotelChannelSignature.TIMESTAMP_HEADER));
        } catch (NumberFormatException e) {
            return "TIMESTAMP_INVALID";
        }
        if (Math.abs(System.currentTimeMillis() - timestamp) > properties.getMaxClockSkewMs()) {
            return "TIMESTAMP_OUT_OF_WINDOW";
        }
        String signature = request.getHeader(HotelChannelSignature.SIGNATURE_HEADER);
        if (!HotelChannelSignature.isSignatureFormatValid(signature)) {
            return "SIGNATURE_FORMAT_INVALID";
        }
        String expected = HotelChannelSignature.sign(channel.getSecret(), channelCode.trim(),
                request.getMethod(), request.getRequestURI(), request.getQueryString(), timestamp,
                request.getCachedBody());
        return HotelChannelSignature.matches(expected, signature) ? null : "SIGNATURE_MISMATCH";
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "CHANNEL_UNAUTHORIZED");
        body.put("message", "渠道签名校验失败");
        String requestId = RequestIdContext.get();
        if (requestId != null && !requestId.isBlank()) {
            body.put("requestId", requestId);
        }
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }
}
