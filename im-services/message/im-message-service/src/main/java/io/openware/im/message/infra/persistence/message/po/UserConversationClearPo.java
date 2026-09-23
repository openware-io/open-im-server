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
  @TableField("created_at") private LocalDateTime createdAt;
}
