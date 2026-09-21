package com.gvchat.im.message.infra.persistence.message.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.MsgType;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("msg_message_favorite")
public class MessageFavoritePo {
  @TableId(type = IdType.AUTO) private Long id;
  @TableField("user_id") private Long userId;
  @TableField("msg_id") private String msgId;
  @TableField("peer_id") private String peerId;
  private ChatType chatType;
  @TableField("created_at") private LocalDateTime createdAt;
  /** 收藏时原消息类型快照（V13 新增，可空：历史行与密聊收藏为空）。 */
  @TableField("msg_type_snapshot") private MsgType msgTypeSnapshot;
  @TableField("content_snapshot") private String contentSnapshot;
  @TableField("from_user_id_snapshot") private Long fromUserIdSnapshot;
  @TableField("sender_username_snapshot") private String senderUsernameSnapshot;
  @TableField("sent_at_snapshot") private LocalDateTime sentAtSnapshot;
}
