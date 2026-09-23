package io.openware.im.user.infra.persistence.account.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user_admin_status_operation")
public class UserStatusOperationPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("idempotency_key")
  private String idempotencyKey;
  @TableField("user_id")
  private Long userId;
  @TableField("expected_version")
  private Long expectedVersion;
  @TableField("previous_status")
  private String previousStatus;
  @TableField("current_status")
  private String currentStatus;
  @TableField("status_version")
  private Long statusVersion;
  @TableField("operator_id")
  private Long operatorId;
  private String reason;
  @TableField("correlation_id")
  private String correlationId;
  @TableField("request_version")
  private Integer requestVersion;
  @TableField("created_at")
  private LocalDateTime createdAt;
}
