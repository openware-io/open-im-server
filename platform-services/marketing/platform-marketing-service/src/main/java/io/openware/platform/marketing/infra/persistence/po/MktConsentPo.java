package io.openware.platform.marketing.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 营销同意（mkt_consent）。营销授权与履约通知通过 consent_type 区分，分开存储。
 */
@Getter
@Setter
@TableName("mkt_consent")
public class MktConsentPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private Long tenantId;
    private String consentType;
    private String channel;
    private String policyVersion;
    private Integer granted;
    private String source;
    private LocalDateTime occurredAt;
    private LocalDateTime withdrawnAt;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
