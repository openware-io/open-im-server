package com.gvchat.common.sms.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 短信渠道配置：accessKey/secretKey 为平台加密托管占位，不落明文。 */
@Getter
@Setter
@TableName("sms_channel_config")
public class SmsChannelConfigPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String provider;
    private String accessKey;
    private String secretKey;
    private String signName;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
