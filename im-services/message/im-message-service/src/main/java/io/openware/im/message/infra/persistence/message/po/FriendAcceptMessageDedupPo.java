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
  /** 基线重建（批次 2）补齐的审计列；写入方显式写入 created_at/updated_at，created_by/updated_by 由默认值兜底。 */
  @TableField("created_by") private Long createdBy;
  @TableField("created_at") private LocalDateTime createdAt;
  @TableField("updated_by") private Long updatedBy;
  @TableField("updated_at") private LocalDateTime updatedAt;
}
