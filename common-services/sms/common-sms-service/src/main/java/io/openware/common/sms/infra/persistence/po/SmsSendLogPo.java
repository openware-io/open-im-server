package io.openware.common.sms.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 短信发送日志：手机号只落 SHA-256 摘要与脱敏展示，不落明文。 */
@Getter
@Setter
@TableName("sms_send_log")
public class SmsSendLogPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String phoneDigest;
    private String phoneMasked;
    private String templateCode;
    private String provider;
    private String status;
    private Integer retryCount;
    private String providerMessageId;
    private String rawResponse;
    private String idempotencyKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
