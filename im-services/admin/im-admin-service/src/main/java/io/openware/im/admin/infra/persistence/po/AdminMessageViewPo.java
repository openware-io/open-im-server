package io.openware.im.admin.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("adm_message_view")
public class AdminMessageViewPo {
  // 主键改为自增 `id`（`msg_id` 降为唯一键 uk_adm_message_view_msg_id）。
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("msg_id")
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
