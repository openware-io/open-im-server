package io.openware.im.message.infra.persistence.message.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 会话清空标记（per-user）：客户端按 clearedAt 删除本地早于该时刻的消息。 */
@Getter
@Setter
@TableName("msg_user_conversation_clear")
public class UserConversationClearPo {
  @TableId(type = IdType.AUTO) private Long id;
  @TableField("user_id") private Long userId;
  @TableField("conversation_id") private String conversationId;
  @TableField("chat_type") private String chatType;
  @TableField("cleared_at") private LocalDateTime clearedAt;
  @TableField("cleared_by") private Long clearedBy;
  /** 基线重建（批次 2）补齐的审计列；写入方 {@code UserConversationClearMapper.upsert} 显式写入 created_at。 */
  @TableField("created_by") private Long createdBy;
  @TableField("created_at") private LocalDateTime createdAt;
  @TableField("updated_by") private Long updatedBy;
  @TableField("updated_at") private LocalDateTime updatedAt;
}
