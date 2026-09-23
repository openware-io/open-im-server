package io.openware.im.admin.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("adm_message_view")
public class AdminMessageViewPo {
  @TableId
  private String msgId;
  private Long fromUserId;
  private String toId;
  private String conversationId;
  private String chatType;
  private String msgType;
  private String content;
  private String clientMsgId;
  private String replyMsgId;
  private String atUsersJson;
  private String status;
  private LocalDateTime createdAt;
}
