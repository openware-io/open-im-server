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
  @TableField("created_at") private LocalDateTime createdAt;
}
