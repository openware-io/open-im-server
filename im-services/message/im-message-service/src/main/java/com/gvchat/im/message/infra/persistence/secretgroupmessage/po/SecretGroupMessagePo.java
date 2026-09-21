package com.gvchat.im.message.infra.persistence.secretgroupmessage.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("msg_secret_group_message")
public class SecretGroupMessagePo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("secret_group_id")
  private Long secretGroupId;
  @TableField("msg_id")
  private String msgId;
  @TableField("from_user_id")
  private Long fromUserId;
  @TableField("recipient_user_id")
  private Long recipientUserId;
  private String ciphertext;
  private Long seq;
  private String status;
  @TableField("destroy_at")
  private LocalDateTime destroyAt;
  @TableField("created_at")
  private LocalDateTime createdAt;
}
