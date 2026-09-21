package com.gvchat.im.admin.infra.clientrelease.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gvchat.im.admin.domain.clientrelease.model.ReleaseOperationAction;
import com.gvchat.im.admin.domain.clientrelease.model.ReleaseStatus;
import com.gvchat.im.admin.infra.clientrelease.persistence.typehandler.ReleaseOperationActionTypeHandler;
import com.gvchat.im.admin.infra.clientrelease.persistence.typehandler.ReleaseStatusTypeHandler;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName(value = "adm_client_release_audit_log", autoResultMap = true)
public class ClientReleaseAuditLogPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("release_id")
  private Long releaseId;
  @TableField("policy_id")
  private Long policyId;
  @TableField(typeHandler = ReleaseOperationActionTypeHandler.class)
  private ReleaseOperationAction action;
  @TableField(value = "before_status", typeHandler = ReleaseStatusTypeHandler.class)
  private ReleaseStatus beforeStatus;
  @TableField(value = "after_status", typeHandler = ReleaseStatusTypeHandler.class)
  private ReleaseStatus afterStatus;
  @TableField("request_id")
  private String requestId;
  @TableField("idempotency_key")
  private String idempotencyKey;
  private String reason;
  @TableField("payload_digest")
  private String payloadDigest;
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
