package com.gvchat.common.sms.domain.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 短信发送日志领域实体：手机号只落摘要与脱敏展示，不落明文。 */
@Getter
@Setter
@NoArgsConstructor
public class SmsSendLog {
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
