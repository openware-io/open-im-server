package io.openware.platform.marketing.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.marketing.domain.coupon.model.DiscountType;
import io.openware.platform.marketing.domain.coupon.model.MktCoupon;
import io.openware.platform.marketing.domain.coupon.model.MktCouponIssuance;
import io.openware.platform.marketing.domain.coupon.model.MktCouponRedemption;
import io.openware.platform.marketing.domain.coupon.readmodel.PageResult;
import io.openware.platform.marketing.domain.coupon.repository.DomainEventOutbox;
import io.openware.platform.marketing.domain.coupon.repository.MktCouponIssuanceRepository;
import io.openware.platform.marketing.domain.coupon.repository.MktCouponRedemptionRepository;
import io.openware.platform.marketing.domain.coupon.repository.MktCouponRepository;
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
 * uk_mkt_coupon_issuance_idempotency(tenant_id, idempotency_key) 由 Flyway 保证。
 * 核销兜底：唯一键 uk_mkt_coupon_redemption_issuance(issuance_id) 保证同一发放仅核销一次。
 *
 * <p>持久化经领域端口；优惠计算与状态判定由领域模型承担。核销的并发正确性依赖端口
 * {@link MktCouponIssuanceRepository#markRedeemed} 的单条条件更新（乐观锁 + 状态机），
 * 应用层不得改为"先读后判再写"。
 */
@Service
public class CouponApplicationService {
    private final MktCouponRepository couponRepository;
    private final MktCouponIssuanceRepository issuanceRepository;
    private final MktCouponRedemptionRepository redemptionRepository;
    private final DomainEventOutbox outbox;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CouponApplicationService(MktCouponRepository couponRepository,
                                    MktCouponIssuanceRepository issuanceRepository,
                                    MktCouponRedemptionRepository redemptionRepository,
                                    DomainEventOutbox outbox) {
        this.couponRepository = couponRepository;
        this.issuanceRepository = issuanceRepository;
        this.redemptionRepository = redemptionRepository;
        this.outbox = outbox;
    }

    public PageResult<MktCoupon> list(long page, long pageSize, String status) {
        return couponRepository.list(page, pageSize, status);
    }

    @Transactional
    public MktCoupon create(Long campaignId, String discountType, Long discountValue, Long minAmount,
                            Long maxDiscount, Integer usageLimit, Integer perCustomerLimit, String status) {
        DiscountType type = validatedDiscountType(discountType, discountValue);
        MktCoupon coupon = MktCoupon.create(TenantContextHolder.tenantIdOrNull(), campaignId, type,
                discountValue, minAmount, maxDiscount, usageLimit, perCustomerLimit, status);
        return couponRepository.save(coupon);
    }

    @Transactional
    public MktCouponIssuance issue(Long couponId, Long customerId, String idempotencyKey) {
        if (customerId == null) throw new ApiException(400, "CUSTOMER_REQUIRED", "缺少 customerId");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "缺少 Idempotency-Key");
        }
        MktCoupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ApiException(404, "COUPON_NOT_FOUND", "优惠券不存在"));
        if (!coupon.issuable()) throw new ApiException(422, "COUPON_STATUS_INVALID", "优惠券不可发放");

        MktCouponIssuance existing = issuanceRepository.findUnique(couponId, customerId).orElse(null);
        if (existing != null) return existing;
        existing = issuanceRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) return existing;

        return issuanceRepository.save(MktCouponIssuance.issue(coupon.tenantId(), couponId, customerId,
                idempotencyKey, LocalDateTime.now()));
    }

    @Transactional
    public RedeemResult redeem(Long couponId, Long customerId, Long orderId, Long amount, Integer expectedVersion) {
        if (customerId == null) throw new ApiException(400, "CUSTOMER_REQUIRED", "缺少 customerId");
        if (orderId == null) throw new ApiException(400, "ORDER_REQUIRED", "缺少 orderId");
        if (amount == null || amount <= 0) throw new ApiException(400, "AMOUNT_INVALID", "订单金额必须为正整数(最小货币单位)");

        MktCouponIssuance issuance = issuanceRepository.findUnique(couponId, customerId)
                .orElseThrow(() -> new ApiException(404, "COUPON_ISSUANCE_NOT_FOUND", "未发放给该客户"));
        if (!issuance.redeemable()) {
            throw new ApiException(409, "COUPON_ALREADY_REDEEMED", "发放状态不可核销");
        }

        MktCoupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ApiException(404, "COUPON_NOT_FOUND", "优惠券不存在"));
        if (!coupon.issuable()) {
            throw new ApiException(422, "COUPON_EXPIRED", "优惠券已过期或不可用");
        }
        if (!coupon.meetsThreshold(amount)) {
            throw new ApiException(422, "COUPON_MIN_AMOUNT_NOT_MET", "未达优惠券使用门槛");
        }

        long discountAmount = coupon.discountFor(amount);
        LocalDateTime now = LocalDateTime.now();
        int baseVersion = expectedVersion == null ? issuance.currentVersion() : expectedVersion;

        // 乐观锁 + 状态机：仅 ISSUED 且版本匹配才可核销，同一发放仅核销一次（单条条件更新，见端口契约）
        if (!issuanceRepository.markRedeemed(issuance.id(), baseVersion, orderId, now)) {
            throw new ApiException(409, "COUPON_VERSION_CONFLICT", "核销冲突，发放已核销或版本已变更");
        }

        try {
            redemptionRepository.save(MktCouponRedemption.record(issuance.tenantId(), issuance, orderId,
                    amount, discountAmount, now));
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "COUPON_ALREADY_REDEEMED", "核销不可重复");
        }

        writeOutbox(issuance, orderId, amount, discountAmount, now);

        return new RedeemResult(issuance.id(), couponId, orderId, discountAmount);
    }

    /** 校验优惠类型与优惠值，返回可用的类型；未知类型即拒绝（取值集合封闭，故用枚举表达）。 */
    private DiscountType validatedDiscountType(String discountType, Long discountValue) {
        if (discountType == null || discountType.isBlank()) {
            throw new ApiException(400, "DISCOUNT_TYPE_REQUIRED", "缺少优惠类型");
        }
        DiscountType type = DiscountType.parse(discountType);
        if (type == null) {
            throw new ApiException(400, "DISCOUNT_TYPE_INVALID", "未知优惠类型");
        }
        if (discountValue == null || discountValue <= 0) {
            throw new ApiException(400, "DISCOUNT_VALUE_INVALID", "优惠值必须为正整数(最小货币单位/基点)");
        }
        return type;
    }

    private void writeOutbox(MktCouponIssuance issuance, Long orderId, Long amount, long discountAmount,
                             LocalDateTime now) {
        // 占位：与业务同事务提交；真实实现由 Relay 读取 PENDING 并投递 RocketMQ（topic 见 CouponMqRegistry）
        String eventId = UUID.randomUUID().toString();
        CouponRedeemedEvent event = CouponRedeemedEvent.builder()
                .eventId(eventId)
                .tenantId(issuance.tenantId())
                .issuanceId(issuance.id())
                .couponId(issuance.couponId())
                .customerId(issuance.customerId())
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
        // tenantId 显式传入：出站记录建表有 tenant_id 列，事件载荷里也带租户上下文
        outbox.append(issuance.tenantId(), "coupon", String.valueOf(issuance.id()),
                MktEventTypes.COUPON_REDEEMED, eventId, payload);
    }

    /** 核销结果（discountAmount 最小货币单位整数）。 */
    public record RedeemResult(Long issuanceId, Long couponId, Long orderId, Long discountAmount) {}
}
