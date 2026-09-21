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
@TableName("conversation_group")
public class ConversationGroupPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String name;
  private String avatar;
  @TableField("owner_id")
  private Long ownerId;
  private String announcement;
  @TableField("max_members")
  private Integer maxMembers;
  @TableField("allow_member_invite")
  private Boolean allowMemberInvite;
  @TableField("allow_member_friend_request")
  private Boolean allowMemberFriendRequest;
  @TableField("allow_member_view_account")
  private Boolean allowMemberViewAccount;
  private String status;
  @TableField("authorization_version")
  private Long authorizationVersion;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
