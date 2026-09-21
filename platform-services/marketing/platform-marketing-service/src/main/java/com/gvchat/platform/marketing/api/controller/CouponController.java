package com.gvchat.platform.marketing.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.platform.marketing.application.CouponApplicationService;
import com.gvchat.platform.marketing.infra.persistence.po.MktCouponIssuancePo;
import com.gvchat.platform.marketing.infra.persistence.po.MktCouponPo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 优惠券 API（对齐 SAAS_PLATFORM_05_API.md §8）。发放幂等、核销接订单归属与金额。
 */
@RestController
@RequestMapping("/business/coupons")
public class CouponController {
    private final CouponApplicationService couponService;

    public CouponController(CouponApplicationService couponService) { this.couponService = couponService; }

    /** 优惠券分页列表（当前租户）。 */
    @GetMapping
    public Page<MktCouponPo> list(@RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "20") long pageSize,
                                  @RequestParam(required = false) String status) {
        return couponService.list(page, pageSize, status);
    }

    /** 创建优惠券。 */
    @PostMapping
    public MktCouponPo create(@RequestBody CreateCouponRequest req) {
        return couponService.create(req.campaignId(), req.discountType(), req.discountValue(), req.minAmount(),
                req.maxDiscount(), req.usageLimit(), req.perCustomerLimit(), req.status());
    }

    /**
     * 发放优惠券（幂等，Idempotency-Key + 唯一键兜底）。
     * 头声明为 {@code required = false}，缺失时由应用层统一抛 400 IDEMPOTENCY_KEY_REQUIRED（中文统一体）。
     */
    @PostMapping("/{id}/issue")
    public MktCouponIssuancePo issue(@PathVariable Long id,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                     @RequestBody IssueCouponRequest req) {
        return couponService.issue(id, req.customerId(), idempotencyKey);
    }

    /** 核销（接订单归属 + 金额，同一发放仅核销一次，返回核销结果）。 */
    @PostMapping("/{id}/redeem")
    public CouponApplicationService.RedeemResult redeem(@PathVariable Long id, @RequestBody RedeemCouponRequest req) {
        return couponService.redeem(id, req.customerId(), req.orderId(), req.amount(), req.expectedVersion());
    }

    public record CreateCouponRequest(Long campaignId, String discountType, Long discountValue,
                                      Long minAmount, Long maxDiscount, Integer usageLimit,
                                      Integer perCustomerLimit, String status) {}
    public record IssueCouponRequest(Long customerId) {}
    public record RedeemCouponRequest(Long customerId, Long orderId, Long amount, Integer expectedVersion) {}
}
