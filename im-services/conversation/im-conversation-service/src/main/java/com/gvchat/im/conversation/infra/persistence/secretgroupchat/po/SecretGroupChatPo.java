package com.gvchat.im.conversation.infra.persistence.secretgroupchat.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("conversation_secret_group_chat")
public class SecretGroupChatPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("owner_user_id")
  private Long ownerUserId;
  private String status;
  private String name;
  private String announcement;
  @TableField("safe_code")
  private String safeCode;
  @TableField("destroy_policy")
  private String destroyPolicy;
  @TableField("anonymous_enabled")
  private Boolean anonymousEnabled;
  @TableField("pinned_msg_id")
  private String pinnedMsgId;
  @TableField("pinned_at")
  private LocalDateTime pinnedAt;
  @TableField("invite_token")
  private String inviteToken;
  @TableField("invite_expires_at")
  private LocalDateTime inviteExpiresAt;
  @TableField("owner_only_post")
  private Boolean ownerOnlyPost;
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
