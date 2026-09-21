package com.gvchat.im.admin.infra.clientrelease.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gvchat.im.admin.domain.clientrelease.model.ReleaseOperationAction;
import com.gvchat.im.admin.infra.clientrelease.persistence.typehandler.ReleaseOperationActionTypeHandler;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName(value = "adm_client_release_operation", autoResultMap = true)
public class ClientReleaseOperationPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("idempotency_key")
  private String idempotencyKey;
  @TableField(typeHandler = ReleaseOperationActionTypeHandler.class)
  private ReleaseOperationAction action;
  @TableField("request_digest")
  private String requestDigest;
  @TableField("release_id")
  private Long releaseId;
  @TableField("policy_id")
  private Long policyId;
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
