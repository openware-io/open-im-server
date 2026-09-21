package com.gvchat.common.payment.api.controller;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.payment.application.CollectApplicationService;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * 内部只读接口（Gateway 不外暴露 /internal/**）：供 order 域账单展示「已收分项」。
 *
 * <p>内部 HMAC 鉴权占位与 platform-customer-service 同口径：校验
 * X-IM-Service-Source / Timestamp / Signature（sha256(source:timestamp:secret)）。
 */
@RestController
@RequestMapping("/internal/payment")
public class InternalPaymentQueryController {
    private static final String SOURCE_HEADER = "X-IM-Service-Source";
    private static final String TIMESTAMP_HEADER = "X-IM-Service-Timestamp";
    private static final String SIGNATURE_HEADER = "X-IM-Service-Signature";

    private final CollectApplicationService collectService;
    private final HttpServletRequest request;
    private final String internalSecret;

    public InternalPaymentQueryController(CollectApplicationService collectService, HttpServletRequest request,
                                          @Value("${app.internal-auth.secret:gv-im-internal-dev-secret}") String internalSecret) {
        this.collectService = collectService;
        this.request = request;
        this.internalSecret = internalSecret;
    }

    /** 按订单汇总已收分项：现金 / A380币（储值）/ 积分。 */
    @GetMapping("/orders/{orderId}/collected")
    public CollectApplicationService.OrderCollected collected(@PathVariable Long orderId) {
        verifyInternalAuth();
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return collectService.orderCollected(context.tenantId(), orderId);
    }

    private void verifyInternalAuth() {
        String source = request.getHeader(SOURCE_HEADER);
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (source == null || source.isBlank() || timestamp == null || signature == null) {
            throw new ApiException(401, "INVALID_INTERNAL_SERVICE_AUTHENTICATION", "内部服务鉴权失败");
        }
        String expected = sha256Hex(source + ":" + timestamp + ":" + internalSecret);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(401, "INVALID_INTERNAL_SERVICE_AUTHENTICATION", "内部服务鉴权失败");
        }
    }

    private String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法计算内部服务签名", e);
        }
    }
}
