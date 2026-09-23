package io.openware.im.message.infra.persistence.secretmessage.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("msg_secret_message_destroyed")
public class SecretMessageDestroyedPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("secret_chat_id")
  private Long secretChatId;
  @TableField("msg_id")
  private String msgId;
  @TableField("destroy_at")
  private LocalDateTime destroyAt;
  private String reason;
}
