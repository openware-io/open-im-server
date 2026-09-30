package io.openware.platform.customer.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.application.PointApplicationService;
import io.openware.platform.customer.application.WalletApplicationService;
import io.openware.platform.customer.infra.persistence.po.CstPointAccountPo;
import io.openware.platform.customer.infra.persistence.po.CstWalletAccountPo;
import jakarta.servlet.http.HttpServletRequest;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import org.springframework.web.bind.annotation.*;


/**
 * 内部扣减/归还端点（供 common-payment-service 组合收款调用，Gateway 不外暴露 /internal/**）。
 * 租户由 X-Tenant-Context 头经 TenantContextFilter 注入，MyBatis 租户拦截器自动附加 tenant_id；
 * 请求体 tenantId 仅作业务参数，不作为权限依据。
 *
 * {@code /internal/customer/**} 由 SDK 鉴权版本 2 过滤器统一验证 HMAC、时间窗和写请求防重放。
 */
@RestController
@RequestMapping("/internal/customer")
public class InternalCustomerController {
    private final WalletApplicationService walletService;
    private final PointApplicationService pointService;
    private final HttpServletRequest request;

    public InternalCustomerController(WalletApplicationService walletService, PointApplicationService pointService,
                                      HttpServletRequest request) {
        this.walletService = walletService;
        this.pointService = pointService;
        this.request = request;
    }

    /** 储值扣减（CONSUME，扣减前校验余额，幂等）。 */
    @PostMapping("/wallets/deduct")
    public WalletDeductResponse deductWallet(@RequestBody WalletDeductRequest req) {
        verifyInternalAuth();
        Long storeId = requireStoreContext();
        CstWalletAccountPo po = walletService.consume(storeId, req.customerId(), req.amount(), req.currency(), req.orderId(),
                req.idempotencyKey());
        return new WalletDeductResponse(po.getId(), po.getCustomerId(), po.getAvailableAmount(), po.getFrozenAmount(),
                po.getCurrencyCode());
    }

    /** 储值释放（组合收款失败补偿，RELEASE，幂等）。 */
    @PostMapping("/wallets/release")
    public WalletReleaseResponse releaseWallet(@RequestBody WalletReleaseRequest req) {
        verifyInternalAuth();
        Long storeId = requireStoreContext();
        CstWalletAccountPo po = walletService.release(storeId, req.customerId(), req.amount(), req.currency(), req.orderId(),
                req.idempotencyKey());
        return new WalletReleaseResponse(po.getId(), po.getCustomerId(), po.getAvailableAmount(), po.getFrozenAmount(),
                po.getCurrencyCode());
    }

    /** 积分抵扣（REDEEM，扣减前校验余额，幂等）。 */
    @PostMapping("/points/redeem")
    public PointRedeemResponse redeemPoints(@RequestBody PointRedeemRequest req) {
        verifyInternalAuth();
        Long storeId = requireStoreContext();
        CstPointAccountPo po = pointService.redeem(storeId, req.customerId(), req.points(), req.orderId(), req.idempotencyKey());
        return new PointRedeemResponse(po.getId(), po.getCustomerId(), po.getAvailablePoints(), po.getFrozenPoints());
    }

    /** 积分释放（组合收款失败补偿，REVERSE，幂等）。 */
    @PostMapping("/points/release")
    public PointReleaseResponse releasePoints(@RequestBody PointReleaseRequest req) {
        verifyInternalAuth();
        Long storeId = requireStoreContext();
        CstPointAccountPo po = pointService.release(storeId, req.customerId(), req.points(), req.orderId(), req.idempotencyKey());
        return new PointReleaseResponse(po.getId(), po.getCustomerId(), po.getAvailablePoints(), po.getFrozenPoints());
    }

    /** 收款确认后的积分获得；仅由内部支付服务调用，收入金额由服务端账单提供。 */
    @PostMapping("/points/earn")
    public PointEarnResponse earnPoints(@RequestBody PointEarnRequest req) {
        verifyInternalAuth();
        Long storeId = requireStoreContext();
        CstPointAccountPo po = pointService.earn(storeId, req.customerId(), req.eligibleAmountMinor(), req.orderId(), req.idempotencyKey());
        return new PointEarnResponse(po.getId(), po.getCustomerId(), po.getAvailablePoints(), po.getFrozenPoints());
    }

    @GetMapping("/points/amount-to-points")
    public AmountToPointsResponse amountToPoints(@RequestParam long amountMinor) {
        verifyInternalAuth();
        TenantContext context = TenantContextHolder.get();
        Long storeId = context == null ? null : context.storeId();
        return new AmountToPointsResponse(amountMinor, pointService.pointsForAmount(storeId, amountMinor));
    }

    private Long requireStoreContext() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        if (context.storeId() == null) {
            throw new ApiException(400, "STORE_CONTEXT_REQUIRED", "客户资产写操作必须在门店上下文中发起");
        }
        return context.storeId();
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

    private void verifyInternalAuth() {
        if (!Boolean.TRUE.equals(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE))) {
            throw new ApiException(401, "INVALID_INTERNAL_SERVICE_AUTHENTICATION", "内部服务鉴权失败");
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
    public record PointEarnRequest(Long customerId, Long eligibleAmountMinor, Long orderId, String idempotencyKey) {}
    public record PointEarnResponse(Long pointAccountId, Long customerId, Long availablePoints, Long frozenPoints) {}
    public record AmountToPointsResponse(long amountMinor, long points) {}
    public record PointReleaseResponse(Long pointAccountId, Long customerId, Long availablePoints, Long frozenPoints) {}
    public record WalletBalanceResponse(Long customerId, Long availableAmount, Long frozenAmount, String currencyCode) {}
    public record PointBalanceResponse(Long customerId, Long availablePoints, Long frozenPoints) {}
}
