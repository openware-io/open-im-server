package io.openware.im.message.infra.persistence.message.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("msg_friend_accept_message")
public class FriendAcceptMessageDedupPo {
  @TableId(type = IdType.AUTO) private Long id;
  @TableField("request_id") private Long requestId;
  @TableField("event_id") private String eventId;
  private String status;
  @TableField("msg_id") private String msgId;
  @TableField("created_at") private LocalDateTime createdAt;
  @TableField("updated_at") private LocalDateTime updatedAt;
}
