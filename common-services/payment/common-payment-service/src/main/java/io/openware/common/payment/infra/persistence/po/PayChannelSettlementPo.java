package io.openware.common.payment.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 渠道回调结算幂等台账（{@code pay_channel_settlement}）。
 *
 * <p>唯一键 {@code (provider, transaction_id, kind)} 是重复回调回放与并发去重的最终判据；
 * 收款/退款分别记账，互不覆盖。
 */
@Getter
@Setter
@TableName("pay_channel_settlement")
public class PayChannelSettlementPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private Long orderId;
    private String provider;
    private String transactionId;
    /** 渠道回调事件 ID（Stripe event id / 微信 v3 通知 id / 支付宝 notify_id），追溯用。 */
    private String eventId;
    /** PAYMENT / REFUND。 */
    private String kind;
    /** 本次结算金额（最小货币单位）。 */
    private BigDecimal amount;
    private String currencyCode;
    /** SUCCEEDED / REFUNDED。 */
    private String status;
    private Long payIntentId;
    private Long payTransactionId;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
