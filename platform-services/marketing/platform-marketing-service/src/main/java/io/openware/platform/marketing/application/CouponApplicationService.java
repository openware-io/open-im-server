package io.openware.platform.marketing.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.platform.marketing.infra.persistence.mapper.CouponIssuanceMapper;
import io.openware.platform.marketing.infra.persistence.mapper.CouponMapper;
import io.openware.platform.marketing.infra.persistence.mapper.CouponRedemptionMapper;
import io.openware.platform.marketing.infra.persistence.mapper.EventOutboxMapper;
import io.openware.platform.marketing.infra.persistence.po.MktCouponIssuancePo;
import io.openware.platform.marketing.infra.persistence.po.MktCouponPo;
import io.openware.platform.marketing.infra.persistence.po.MktCouponRedemptionPo;
import io.openware.platform.marketing.infra.persistence.po.MktEventOutboxPo;
import io.openware.protocol.mq.event.CouponRedeemedEvent;
import io.openware.protocol.mq.event.MktEventTypes;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 优惠券应用服务。金额最小货币单位整数；发放幂等、核销接订单归属+金额且不可重复。
 * 幂等兜底：唯一键 uk_mkt_coupon_issuance_customer(tenant_id, coupon_id, customer_id) 与
 * uk_mkt_coupon_issuance_idem(tenant_id, idempotency_key) 由 Flyway 保证。
 * 核销兜底：唯一键 uk_mkt_coupon_redemption_issuance(issuance_id) 保证同一发放仅核销一次。
 */
@Service
public class CouponApplicationService {
    private final CouponMapper couponMapper;
    private final CouponIssuanceMapper issuanceMapper;
    private final CouponRedemptionMapper redemptionMapper;
    private final EventOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CouponApplicationService(CouponMapper couponMapper, CouponIssuanceMapper issuanceMapper,
                                    CouponRedemptionMapper redemptionMapper, EventOutboxMapper outboxMapper) {
        this.couponMapper = couponMapper;
        this.issuanceMapper = issuanceMapper;
        this.redemptionMapper = redemptionMapper;
        this.outboxMapper = outboxMapper;
    }

    public Page<MktCouponPo> list(long page, long pageSize, String status) {
        LambdaQueryWrapper<MktCouponPo> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) qw.eq(MktCouponPo::getStatus, status);
        qw.orderByDesc(MktCouponPo::getId);
        return couponMapper.selectPage(new Page<>(page, pageSize), qw);
    }

    @Transactional
    public MktCouponPo create(Long campaignId, String discountType, Long discountValue, Long minAmount,
                              Long maxDiscount, Integer usageLimit, Integer perCustomerLimit, String status) {
        validateDiscount(discountType, discountValue);
        MktCouponPo po = new MktCouponPo();
        po.setCampaignId(campaignId);
        po.setDiscountType(discountType);
        po.setDiscountValue(discountValue);
        po.setMinAmount(minAmount == null ? 0L : minAmount);
        po.setMaxDiscount(maxDiscount == null ? 0L : maxDiscount);
        po.setUsageLimit(usageLimit == null ? 1 : usageLimit);
        po.setPerCustomerLimit(perCustomerLimit == null ? 1 : perCustomerLimit);
        po.setStatus(status == null || status.isBlank() ? "ACTIVE" : status);
        po.setVersion(0);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        couponMapper.insert(po);
        return po;
    }

    @Transactional
    public MktCouponIssuancePo issue(Long couponId, Long customerId, String idempotencyKey) {
        if (customerId == null) throw new ApiException(400, "CUSTOMER_REQUIRED", "缺少 customerId");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "缺少 Idempotency-Key");
        }
        MktCouponPo coupon = couponMapper.selectById(couponId);
        if (coupon == null) throw new ApiException(404, "COUPON_NOT_FOUND", "优惠券不存在");
        if (!"ACTIVE".equals(coupon.getStatus())) throw new ApiException(422, "COUPON_STATUS_INVALID", "优惠券不可发放");

        MktCouponIssuancePo existing = findIssuance(couponId, customerId);
        if (existing != null) return existing;
        existing = findByKey(idempotencyKey);
        if (existing != null) return existing;

        MktCouponIssuancePo po = new MktCouponIssuancePo();
        po.setCouponId(couponId);
        po.setCustomerId(customerId);
        po.setIssuedAt(LocalDateTime.now());
        po.setStatus("ISSUED");
        po.setIdempotencyKey(idempotencyKey);
        po.setVersion(0);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        issuanceMapper.insert(po);
        return po;
    }

    @Transactional
    public RedeemResult redeem(Long couponId, Long customerId, Long orderId, Long amount, Integer expectedVersion) {
        if (customerId == null) throw new ApiException(400, "CUSTOMER_REQUIRED", "缺少 customerId");
        if (orderId == null) throw new ApiException(400, "ORDER_REQUIRED", "缺少 orderId");
        if (amount == null || amount <= 0) throw new ApiException(400, "AMOUNT_INVALID", "订单金额必须为正整数(最小货币单位)");

        MktCouponIssuancePo issuance = findIssuance(couponId, customerId);
        if (issuance == null) throw new ApiException(404, "COUPON_ISSUANCE_NOT_FOUND", "未发放给该客户");
        if (!"ISSUED".equals(issuance.getStatus())) {
            throw new ApiException(409, "COUPON_ALREADY_REDEEMED", "发放状态不可核销");
        }

        MktCouponPo coupon = couponMapper.selectById(couponId);
        if (coupon == null) throw new ApiException(404, "COUPON_NOT_FOUND", "优惠券不存在");
        if (!"ACTIVE".equals(coupon.getStatus())) {
            throw new ApiException(422, "COUPON_EXPIRED", "优惠券已过期或不可用");
        }
        if (coupon.getMinAmount() != null && amount < coupon.getMinAmount()) {
            throw new ApiException(422, "COUPON_MIN_AMOUNT_NOT_MET", "未达优惠券使用门槛");
        }

        long discountAmount = computeDiscount(coupon, amount);
        LocalDateTime now = LocalDateTime.now();
        int baseVersion = expectedVersion == null ? (issuance.getVersion() == null ? 0 : issuance.getVersion()) : expectedVersion;

        // 乐观锁 + 状态机：仅 ISSUED 且版本匹配才可核销，同一发放仅核销一次
        LambdaUpdateWrapper<MktCouponIssuancePo> uw = new LambdaUpdateWrapper<>();
        uw.eq(MktCouponIssuancePo::getId, issuance.getId())
          .eq(MktCouponIssuancePo::getStatus, "ISSUED")
          .eq(MktCouponIssuancePo::getVersion, baseVersion)
          .set(MktCouponIssuancePo::getStatus, "USED")
          .set(MktCouponIssuancePo::getUsedAt, now)
          .set(MktCouponIssuancePo::getOrderId, orderId)
          .set(MktCouponIssuancePo::getVersion, baseVersion + 1)
          .set(MktCouponIssuancePo::getUpdatedAt, now);
        int updated = issuanceMapper.update(null, uw);
        if (updated != 1) {
            throw new ApiException(409, "COUPON_VERSION_CONFLICT", "核销冲突，发放已核销或版本已变更");
        }

        MktCouponRedemptionPo redemption = new MktCouponRedemptionPo();
        redemption.setIssuanceId(issuance.getId());
        redemption.setCouponId(couponId);
        redemption.setCustomerId(customerId);
        redemption.setOrderId(orderId);
        redemption.setAmount(amount);
        redemption.setDiscountAmount(discountAmount);
        redemption.setRedeemedAt(now);
        redemption.setVersion(0);
        redemption.setCreatedAt(now);
        redemption.setUpdatedAt(now);
        try {
            redemptionMapper.insert(redemption); // 唯一键 uk_mkt_coupon_redemption_issuance 兜底
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "COUPON_ALREADY_REDEEMED", "核销不可重复");
        }

        writeOutbox(issuance, orderId, amount, discountAmount, now);

        return new RedeemResult(issuance.getId(), couponId, orderId, discountAmount);
    }

    private MktCouponIssuancePo findIssuance(Long couponId, Long customerId) {
        LambdaQueryWrapper<MktCouponIssuancePo> qw = new LambdaQueryWrapper<>();
        qw.eq(MktCouponIssuancePo::getCouponId, couponId)
          .eq(MktCouponIssuancePo::getCustomerId, customerId)
          .last("LIMIT 1");
        return issuanceMapper.selectOne(qw);
    }

    private MktCouponIssuancePo findByKey(String idempotencyKey) {
        LambdaQueryWrapper<MktCouponIssuancePo> qw = new LambdaQueryWrapper<>();
        qw.eq(MktCouponIssuancePo::getIdempotencyKey, idempotencyKey).last("LIMIT 1");
        return issuanceMapper.selectOne(qw);
    }

    private void validateDiscount(String discountType, Long discountValue) {
        if (discountType == null || discountType.isBlank()) {
            throw new ApiException(400, "DISCOUNT_TYPE_REQUIRED", "缺少优惠类型");
        }
        if (!"FIXED_AMOUNT".equals(discountType) && !"PERCENTAGE".equals(discountType)) {
            throw new ApiException(400, "DISCOUNT_TYPE_INVALID", "未知优惠类型");
        }
        if (discountValue == null || discountValue <= 0) {
            throw new ApiException(400, "DISCOUNT_VALUE_INVALID", "优惠值必须为正整数(最小货币单位/基点)");
        }
    }

    private long computeDiscount(MktCouponPo coupon, long amount) {
        if ("FIXED_AMOUNT".equals(coupon.getDiscountType())) {
            long face = coupon.getDiscountValue() == null ? 0L : coupon.getDiscountValue();
            return Math.min(face, amount);
        }
        // PERCENTAGE：discount_value 为万分数(基点)，封顶 max_discount(0=不封顶)
        long basis = coupon.getDiscountValue() == null ? 0L : coupon.getDiscountValue();
        long discount = amount * basis / 10000L;
        if (coupon.getMaxDiscount() != null && coupon.getMaxDiscount() > 0) {
            discount = Math.min(discount, coupon.getMaxDiscount());
        }
        return Math.max(discount, 0L);
    }

    private void writeOutbox(MktCouponIssuancePo issuance, Long orderId, Long amount, long discountAmount, LocalDateTime now) {
        // 占位：与业务同事务提交；真实实现由 Relay 读取 PENDING 并投递 RocketMQ（topic 见 CouponMqRegistry）
        String eventId = UUID.randomUUID().toString();
        CouponRedeemedEvent event = CouponRedeemedEvent.builder()
                .eventId(eventId)
                .tenantId(issuance.getTenantId())
                .issuanceId(issuance.getId())
                .couponId(issuance.getCouponId())
                .customerId(issuance.getCustomerId())
                .orderId(orderId)
                .amount(amount)
                .discountAmount(discountAmount)
                .occurredAt(now.toInstant(ZoneOffset.UTC))
                .build();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new ApiException(500, "OUTBOX_SERIALIZE_FAILED", "事件序列化失败");
        }
        MktEventOutboxPo outbox = new MktEventOutboxPo();
        outbox.setEventId(eventId);
        outbox.setEventType(MktEventTypes.COUPON_REDEEMED);
        outbox.setAggregateType("coupon");
        outbox.setAggregateId(String.valueOf(issuance.getId()));
        outbox.setPayloadJson(payload);
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        outboxMapper.insert(outbox);
    }

    /** 核销结果（discountAmount 最小货币单位整数）。 */
    public record RedeemResult(Long issuanceId, Long couponId, Long orderId, Long discountAmount) {}
}
