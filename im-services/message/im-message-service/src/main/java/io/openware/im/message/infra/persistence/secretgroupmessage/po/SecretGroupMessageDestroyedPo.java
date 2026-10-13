package io.openware.im.message.infra.persistence.secretgroupmessage.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("msg_secret_group_message_destroyed")
public class SecretGroupMessageDestroyedPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("secret_group_id")
  private Long secretGroupId;
  @TableField("msg_id")
  private String msgId;
  @TableField("destroy_at")
  private LocalDateTime destroyAt;
  private String reason;
  /** 基线重建（批次 2）补齐的审计列；本表只增不改，写入方由列默认值兜底。 */
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
