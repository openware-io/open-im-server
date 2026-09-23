package io.openware.platform.marketing.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 优惠券核销记录（mkt_coupon_redemption）。同一发放仅核销一次，金额最小货币单位整数。
 */
@Getter
@Setter
@TableName("mkt_coupon_redemption")
public class MktCouponRedemptionPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long issuanceId;
    private Long couponId;
    private Long customerId;
    private Long orderId;
    private Long amount;
    private Long discountAmount;
    private LocalDateTime redeemedAt;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
