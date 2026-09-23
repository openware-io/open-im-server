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
@TableName("msg_secret_message")
public class SecretMessagePo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("secret_chat_id")
  private Long secretChatId;
  @TableField("msg_id")
  private String msgId;
  @TableField("from_user_id")
  private Long fromUserId;
  private String ciphertext;
  private Long seq;
  private String status;
  @TableField("destroy_at")
  private LocalDateTime destroyAt;
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
