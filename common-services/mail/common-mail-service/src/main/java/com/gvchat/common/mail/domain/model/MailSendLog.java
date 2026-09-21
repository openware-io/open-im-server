package com.gvchat.common.mail.domain.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 邮件发送日志领域实体：收件人只落摘要与脱敏展示，不落明文。 */
@Getter
@Setter
@NoArgsConstructor
public class MailSendLog {
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
