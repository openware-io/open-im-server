package io.openware.platform.marketing.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 优惠券（mkt_coupon）。金额使用最小货币单位整数。
 */
@Getter
@Setter
@TableName("mkt_coupon")
public class MktCouponPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long campaignId;
    private String codeDigest;
    private String discountType;
    private Long discountValue;
    private Long minAmount;
    private Long maxDiscount;
    private Integer usageLimit;
    private Integer perCustomerLimit;
    private String status;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
