package com.gvchat.platform.tenant.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("iam_approval")
public class IamApprovalPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String actionType;
    private String resourceType;
    private String resourceId;
    private Long operatorId;
    private String detailJson;
    private String status;
    private String idempotencyKey;
    private Long approverId;
    private String reviewComment;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime reviewedAt;
}
