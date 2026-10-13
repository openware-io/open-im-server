package io.openware.im.message.infra.persistence.message.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.openware.common.enums.ChatType;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 用户「删除仅我」消息墓碑：仅对该用户隐藏，其余成员可见性不变。 */
@Getter
@Setter
@TableName("msg_user_deleted_message")
public class UserDeletedMessagePo {
  @TableId(type = IdType.AUTO) private Long id;
  @TableField("user_id") private Long userId;
  @TableField("msg_id") private String msgId;
  @TableField("conversation_id") private String conversationId;
  private ChatType chatType;
  /** 基线重建（批次 2）补齐的审计列；写入方 {@code UserDeletedMessageMapper.insertIgnore} 显式写入 created_at。 */
  @TableField("created_by") private Long createdBy;
  @TableField("created_at") private LocalDateTime createdAt;
  @TableField("updated_by") private Long updatedBy;
  @TableField("updated_at") private LocalDateTime updatedAt;
}
