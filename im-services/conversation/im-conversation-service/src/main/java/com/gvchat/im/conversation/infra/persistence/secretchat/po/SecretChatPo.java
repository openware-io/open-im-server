package com.gvchat.im.conversation.infra.persistence.secretchat.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("conversation_secret_chat")
public class SecretChatPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("user_a")
  private Long userA;
  @TableField("user_b")
  private Long userB;
  private String status;
  @TableField("safe_code")
  private String safeCode;
  @TableField("destroy_policy")
  private String destroyPolicy;
  @TableField("user_a_public_key")
  private String userAPublicKey;
  @TableField("user_b_public_key")
  private String userBPublicKey;
  @TableField("handshake_state")
  private String handshakeState;
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
