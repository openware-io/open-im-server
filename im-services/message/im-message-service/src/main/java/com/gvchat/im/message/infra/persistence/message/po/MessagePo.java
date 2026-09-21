package com.gvchat.im.message.infra.persistence.message.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.MsgStatus;
import com.gvchat.common.enums.MsgType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName(value = "msg_message", autoResultMap = true)
public class MessagePo {
  @TableId(type = IdType.AUTO) private Long id;
  @TableField("msg_id") private String msgId;
  @TableField("conversation_id") private String conversationId;
  private Long seq;
  @TableField("from_user_id") private Long fromUserId;
  @TableField("sender_username") private String senderUsername;
  @TableField("to_id") private String toId;
  private ChatType chatType;
  private MsgType msgType;
  private String content;
  @TableField("client_msg_id") private String clientMsgId;
  @TableField("reply_msg_id") private String replyMsgId;
  @TableField(value = "at_users", typeHandler = JacksonTypeHandler.class) private List<String> atUsers;
  @TableField(value = "media_object_ids", typeHandler = JacksonTypeHandler.class) private List<String> mediaObjectIds;
  private MsgStatus status;
  private Boolean edited;
  @TableField("edited_at") private LocalDateTime editedAt;
  @TableField("created_by") private Long createdBy;
  @TableField("created_at") private LocalDateTime createdAt;
  @TableField("updated_by") private Long updatedBy;
  @TableField("updated_at") private LocalDateTime updatedAt;
}
