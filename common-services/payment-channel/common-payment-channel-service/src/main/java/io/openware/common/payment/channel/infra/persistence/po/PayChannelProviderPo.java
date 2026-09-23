package io.openware.common.payment.channel.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 支付渠道配置（微信/支付宝/Stripe），租户/门店开关，密钥加密托管占位。 */
@Getter
@Setter
@TableName("pay_channel_provider")
public class PayChannelProviderPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private String provider;
    private Integer enabled;
    private String merchantId;
    private String secretEncrypted;
    private String secretKeyRef;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
