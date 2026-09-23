package io.openware.platform.marketing.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 优惠券发放/核销记录（mkt_coupon_issuance）。发放幂等，核销不可重复。
 */
@Getter
@Setter
@TableName("mkt_coupon_issuance")
public class MktCouponIssuancePo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long couponId;
    private Long customerId;
    private LocalDateTime issuedAt;
    private LocalDateTime usedAt;
    private Long orderId;
    private String status;
    private String idempotencyKey;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
