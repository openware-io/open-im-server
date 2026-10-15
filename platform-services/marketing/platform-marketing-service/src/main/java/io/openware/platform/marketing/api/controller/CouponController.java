package io.openware.platform.marketing.api.controller;

import io.openware.platform.marketing.application.CouponApplicationService;
import io.openware.platform.marketing.domain.coupon.model.MktCoupon;
import io.openware.platform.marketing.domain.coupon.model.MktCouponIssuance;
import io.openware.platform.marketing.domain.coupon.readmodel.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 优惠券 API（对齐 SAAS_PLATFORM_05_API.md §8）。发放幂等、核销接订单归属与金额。
 *
 * <p>响应不再直接返回持久化对象：分页由领域侧的 {@link PageResult} 承载（分页插件类型不外泄），
 * 券与发放各自映射为对外视图，按 standards/10 的 D2 规则不暴露记账字段。
 */
@RestController
@RequestMapping("/business/coupons")
public class CouponController {
    private final CouponApplicationService couponService;

    public CouponController(CouponApplicationService couponService) { this.couponService = couponService; }

    /** 优惠券分页列表（当前租户）。 */
    @GetMapping
    public CouponPage list(@RequestParam(defaultValue = "1") long page,
                           @RequestParam(defaultValue = "20") long pageSize,
                           @RequestParam(required = false) String status) {
        PageResult<MktCoupon> result = couponService.list(page, pageSize, status);
        List<CouponView> items = result.items().stream().map(CouponController::toView).toList();
        return new CouponPage(items, result.total(), result.page(), result.pageSize());
    }

    /** 创建优惠券。 */
    @PostMapping
    public CouponView create(@RequestBody CreateCouponRequest req) {
        return toView(couponService.create(req.campaignId(), req.discountType(), req.discountValue(), req.minAmount(),
                req.maxDiscount(), req.usageLimit(), req.perCustomerLimit(), req.status()));
    }

    /**
     * 发放优惠券（幂等，Idempotency-Key + 唯一键兜底）。
     * 头声明为 {@code required = false}，缺失时由应用层统一抛 400 IDEMPOTENCY_KEY_REQUIRED（中文统一体）。
     */
    @PostMapping("/{id}/issue")
    public IssuanceView issue(@PathVariable Long id,
                              @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                              @RequestBody IssueCouponRequest req) {
        return toView(couponService.issue(id, req.customerId(), idempotencyKey));
    }

    /** 核销（接订单归属 + 金额，同一发放仅核销一次，返回核销结果）。 */
    @PostMapping("/{id}/redeem")
    public CouponApplicationService.RedeemResult redeem(@PathVariable Long id, @RequestBody RedeemCouponRequest req) {
        return couponService.redeem(id, req.customerId(), req.orderId(), req.amount(), req.expectedVersion());
    }

    /** 优惠券对外视图：不暴露记账字段；{@code version} 是并发语义，保留。 */
    public record CouponView(Long id, Long tenantId, Long campaignId, String discountType, Long discountValue,
                             Long minAmount, Long maxDiscount, Integer usageLimit, Integer perCustomerLimit,
                             String status, Integer version) {}

    /** 分页响应：形状与既有分页 JSON 的关键字段一致（items/total/page/pageSize）。 */
    public record CouponPage(List<CouponView> items, long total, long page, long pageSize) {}

    /** 发放对外视图：只暴露业务字段。 */
    public record IssuanceView(Long id, Long tenantId, Long couponId, Long customerId,
                               java.time.LocalDateTime issuedAt, java.time.LocalDateTime usedAt,
                               Long orderId, String status, Integer version) {}

    public record CreateCouponRequest(Long campaignId, String discountType, Long discountValue,
                                      Long minAmount, Long maxDiscount, Integer usageLimit,
                                      Integer perCustomerLimit, String status) {}
    public record IssueCouponRequest(Long customerId) {}
    public record RedeemCouponRequest(Long customerId, Long orderId, Long amount, Integer expectedVersion) {}

    private static CouponView toView(MktCoupon coupon) {
        return new CouponView(coupon.id(), coupon.tenantId(), coupon.campaignId(),
                coupon.discountType() == null ? null : coupon.discountType().name(), coupon.discountValue(),
                coupon.minAmount(), coupon.maxDiscount(), coupon.usageLimit(), coupon.perCustomerLimit(),
                coupon.status(), coupon.version());
    }

    private static IssuanceView toView(MktCouponIssuance issuance) {
        return new IssuanceView(issuance.id(), issuance.tenantId(), issuance.couponId(), issuance.customerId(),
                issuance.issuedAt(), issuance.usedAt(), issuance.redemptionOrderId(), issuance.status(),
                issuance.version());
    }
}
