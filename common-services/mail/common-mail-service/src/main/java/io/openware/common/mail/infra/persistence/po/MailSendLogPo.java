package io.openware.common.mail.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 邮件发送日志：收件人只落 SHA-256 摘要与脱敏展示，不落明文。 */
@Getter
@Setter
@TableName("mail_send_log")
public class MailSendLogPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String recipientDigest;
    private String recipientMasked;
    private String templateCode;
    private String subject;
    private String status;
    private Integer retryCount;
    private String providerMessageId;
    private String rawResponse;
    private String idempotencyKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
