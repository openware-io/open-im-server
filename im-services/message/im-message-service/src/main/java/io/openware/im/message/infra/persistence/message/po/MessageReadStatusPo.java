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
@TableName("msg_read_status")
public class MessageReadStatusPo {
  @TableId(type = IdType.AUTO) private Long id;
  @TableField("msg_id") private String msgId;
  @TableField("user_id") private Long userId;
  @TableField("read_at") private LocalDateTime readAt;
}
