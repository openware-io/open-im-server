package io.openware.im.conversation.infra.persistence.secretgroupchat.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("conversation_secret_group_member")
public class SecretGroupMemberPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("secret_group_id")
  private Long secretGroupId;
  @TableField("user_id")
  private Long userId;
  @TableField("device_public_key")
  private String devicePublicKey;
  @TableField("joined_at")
  private LocalDateTime joinedAt;
}
