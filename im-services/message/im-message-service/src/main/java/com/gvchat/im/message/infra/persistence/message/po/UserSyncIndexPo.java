package com.gvchat.im.message.infra.persistence.message.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("msg_user_sync_index")
public class UserSyncIndexPo {
  @TableField("user_id") private Long userId;
  @TableField("sync_seq") private Long syncSeq;
  @TableField("msg_id") private String msgId;
  @TableField("conversation_id") private String conversationId;
  @TableField("created_at") private LocalDateTime createdAt;
}
