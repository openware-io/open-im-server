package com.gvchat.im.conversation.infra.persistence.group.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("conversation_group_member")
public class ConversationMemberPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("group_id")
  private Long groupId;
  @TableField("user_id")
  private Long userId;
  private String role;
  private String nickname;
  @TableField("is_muted")
  private Boolean muted;
  @TableField("muted_until")
  private LocalDateTime mutedUntil;
  @TableField("authorization_version")
  private Long authorizationVersion;
  @TableField("joined_at")
  private LocalDateTime joinedAt;
}
