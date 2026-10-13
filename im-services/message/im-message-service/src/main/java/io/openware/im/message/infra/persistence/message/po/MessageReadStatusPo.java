package io.openware.im.message.infra.persistence.message.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("msg_read_status")
public class MessageReadStatusPo {
  @TableId(type = IdType.AUTO) private Long id;
  @TableField("msg_id") private String msgId;
  @TableField("user_id") private Long userId;
  @TableField("read_at") private LocalDateTime readAt;
  /** 基线重建（批次 2）补齐的审计列；写入方只写 msg_id/user_id/read_at，审计列由列默认值兜底。 */
  @TableField("created_by") private Long createdBy;
  @TableField("created_at") private LocalDateTime createdAt;
  @TableField("updated_by") private Long updatedBy;
  @TableField("updated_at") private LocalDateTime updatedAt;
}
