package io.openware.common.payment.channel.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 支付渠道交易，仅记录渠道侧交易与回调原始报文，不拥有业务资金账本。 */
@Getter
@Setter
@TableName("pay_channel_transaction")
public class PayChannelTransactionPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String transactionId;
    /** 回调事件 ID（Stripe event id / 微信 v3 通知 id / 支付宝 notify_id），重放去重。 */
    private String callbackEventId;
    private String provider;
    private String orderId;
    private Long amount;
    private String currency;
    private String status;
    private String callbackRaw;
    /** 回调结算状态：PENDING/APPLIED/FAILED（调用 payment-service 内部结算接口的结果，失败可重试）。 */
    private String settlementStatus;
    /** 结算调用尝试次数（含失败重投）。 */
    private Integer settlementAttempts;
    /** 最近一次结算失败原因（截断，不含密钥/卡号/完整报文）。 */
    private String settlementError;
    /** 最近一次结算调用时间。 */
    private LocalDateTime settlementUpdatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
