package io.openware.im.user.infra.persistence.cancellation.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user_account_cancellation")
public class AccountCancellationPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("user_id")
  private Long userId;
  private String username;
  private String nickname;
  private String phone;
  private String email;
  private String status;
  @TableField("status_token_hash")
  private String statusTokenHash;
  private String source;
  @TableField("completed_steps")
  private String completedSteps;
  @TableField("requested_ip")
  private String requestedIp;
  @TableField("requested_at")
  private LocalDateTime requestedAt;
  @TableField("processing_at")
  private LocalDateTime processingAt;
  @TableField("completed_at")
  private LocalDateTime completedAt;
  @TableField("failed_reason")
  private String failedReason;
  @TableField("row_version")
  private Long rowVersion;
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
