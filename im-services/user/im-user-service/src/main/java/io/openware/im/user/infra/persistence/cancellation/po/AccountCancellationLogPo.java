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
@TableName("user_account_cancellation_log")
public class AccountCancellationLogPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("cancellation_id")
  private Long cancellationId;
  @TableField("user_id")
  private Long userId;
  private String action;
  private String detail;
  @TableField("operator_type")
  private String operatorType;
  @TableField("operator_id")
  private Long operatorId;
  private String ip;
  @TableField("occurred_at")
  private LocalDateTime occurredAt;
}
