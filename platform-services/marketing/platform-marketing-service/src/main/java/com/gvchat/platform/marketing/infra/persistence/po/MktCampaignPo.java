package com.gvchat.platform.marketing.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 营销活动（mkt_campaign）。平台活动 tenant_id 为空，租户活动带租户边界。
 */
@Getter
@Setter
@TableName("mkt_campaign")
public class MktCampaignPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String campaignType;
    private String name;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Long budgetAmount;
    private String fundingParty;
    private String status;
    private String ruleSnapshotJson;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
