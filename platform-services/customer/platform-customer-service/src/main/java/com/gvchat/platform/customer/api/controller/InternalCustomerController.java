package com.gvchat.platform.customer.api.controller;

import com.gvchat.common.exception.ApiException;
import com.gvchat.platform.customer.application.PointApplicationService;
import com.gvchat.platform.customer.application.WalletApplicationService;
import com.gvchat.platform.customer.infra.persistence.po.CstPointAccountPo;
import com.gvchat.platform.customer.infra.persistence.po.CstWalletAccountPo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * 内部扣减/归还端点（供 common-payment-service 组合收款调用，Gateway 不外暴露 /internal/**）。
 * 租户由 X-Tenant-Context 头经 TenantContextFilter 注入，MyBatis 租户拦截器自动附加 tenant_id；
 * 请求体 tenantId 仅作业务参数，不作为权限依据。
 *
 * 内部 HMAC 鉴权占位：每个内部端点校验 X-IM-Service-*（source + timestamp + signature），
 * 当前为简单签名比对 sha256(source:timestamp:secret)；真实实现应使用 HMAC 共享密钥（见 SDK InternalServiceAuthentication/Filter）。
 */
@RestController
@RequestMapping("/internal/customer")
public class InternalCustomerController {
    private static final String SOURCE_HEADER = "X-IM-Service-Source";
    private static final String TIMESTAMP_HEADER = "X-IM-Service-Timestamp";
    private static final String SIGNATURE_HEADER = "X-IM-Service-Signature";

    private final WalletApplicationService walletService;
    private final PointApplicationService pointService;
    private final HttpServletRequest request;
    private final String internalSecret;

    public InternalCustomerController(WalletApplicationService walletService, PointApplicationService pointService,
                                      HttpServletRequest request,
                                      @Value("${app.internal-auth.secret:gv-im-internal-dev-secret}") String internalSecret) {
        this.walletService = walletService;
        this.pointService = pointService;
        this.request = request;
        this.internalSecret = internalSecret;
    }

    /** 储值扣减（CONSUME，扣减前校验余额，幂等）。 */
    @PostMapping("/wallets/deduct")
    public WalletDeductResponse deductWallet(@RequestBody WalletDeductRequest req) {
        verifyInternalAuth();
        CstWalletAccountPo po = walletService.consume(req.customerId(), req.amount(), req.currency(), req.orderId(),
                req.idempotencyKey());
        return new WalletDeductResponse(po.getId(), po.getCustomerId(), po.getAvailableAmount(), po.getFrozenAmount(),
                po.getCurrencyCode());
    }

    /** 储值释放（组合收款失败补偿，RELEASE，幂等）。 */
    @PostMapping("/wallets/release")
    public WalletReleaseResponse releaseWallet(@RequestBody WalletReleaseRequest req) {
        verifyInternalAuth();
        CstWalletAccountPo po = walletService.release(req.customerId(), req.amount(), req.currency(), req.orderId(),
                req.idempotencyKey());
        return new WalletReleaseResponse(po.getId(), po.getCustomerId(), po.getAvailableAmount(), po.getFrozenAmount(),
                po.getCurrencyCode());
    }

    /** 积分抵扣（REDEEM，扣减前校验余额，幂等）。 */
    @PostMapping("/points/redeem")
    public PointRedeemResponse redeemPoints(@RequestBody PointRedeemRequest req) {
        verifyInternalAuth();
        CstPointAccountPo po = pointService.redeem(req.customerId(), req.points(), req.orderId(), req.idempotencyKey());
        return new PointRedeemResponse(po.getId(), po.getCustomerId(), po.getAvailablePoints(), po.getFrozenPoints());
    }

    /** 积分释放（组合收款失败补偿，REVERSE，幂等）。 */
    @PostMapping("/points/release")
    public PointReleaseResponse releasePoints(@RequestBody PointReleaseRequest req) {
        verifyInternalAuth();
        CstPointAccountPo po = pointService.release(req.customerId(), req.points(), req.orderId(), req.idempotencyKey());
        return new PointReleaseResponse(po.getId(), po.getCustomerId(), po.getAvailablePoints(), po.getFrozenPoints());
    }

    /** 储值余额查询（组合收款抵扣前校验）。 */
    @GetMapping("/wallets/{customerId}")
    public WalletBalanceResponse walletBalance(@PathVariable Long customerId) {
        verifyInternalAuth();
        CstWalletAccountPo po = walletService.balance(customerId);
        return new WalletBalanceResponse(po.getCustomerId(), po.getAvailableAmount(), po.getFrozenAmount(),
                po.getCurrencyCode());
    }

    /** 积分余额查询（组合收款抵扣前校验）。 */
    @GetMapping("/points/{customerId}")
    public PointBalanceResponse pointsBalance(@PathVariable Long customerId) {
        verifyInternalAuth();
        CstPointAccountPo po = pointService.balance(customerId);
        return new PointBalanceResponse(po.getCustomerId(), po.getAvailablePoints(), po.getFrozenPoints());
    }

    /** 简单签名比对占位：sha256(source:timestamp:secret)；真实实现应为 HMAC(共享密钥) + 时间窗/重放防御。 */
    private void verifyInternalAuth() {
        String source = request.getHeader(SOURCE_HEADER);
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (source == null || source.isBlank() || timestamp == null || signature == null) {
            throw new ApiException(401, "INVALID_INTERNAL_SERVICE_AUTHENTICATION", "内部服务鉴权失败");
        }
        String expected = sha256Hex(source + ":" + timestamp + ":" + internalSecret);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
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

    public record WalletDeductRequest(Long customerId, Long amount, String currency, Long orderId,
                                      String idempotencyKey) {}
    public record WalletDeductResponse(Long walletAccountId, Long customerId, Long availableAmount, Long frozenAmount,
                                       String currencyCode) {}
    public record WalletReleaseRequest(Long customerId, Long amount, String currency, Long orderId,
                                       String idempotencyKey) {}
    public record WalletReleaseResponse(Long walletAccountId, Long customerId, Long availableAmount, Long frozenAmount,
                                        String currencyCode) {}
    public record PointRedeemRequest(Long customerId, Long points, Long orderId, String idempotencyKey) {}
    public record PointRedeemResponse(Long pointAccountId, Long customerId, Long availablePoints, Long frozenPoints) {}
    public record PointReleaseRequest(Long customerId, Long points, Long orderId, String idempotencyKey) {}
    public record PointReleaseResponse(Long pointAccountId, Long customerId, Long availablePoints, Long frozenPoints) {}
    public record WalletBalanceResponse(Long customerId, Long availableAmount, Long frozenAmount, String currencyCode) {}
    public record PointBalanceResponse(Long customerId, Long availablePoints, Long frozenPoints) {}
}
