package com.gvchat.im.user.infra.persistence.privacysetting.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user_privacy_setting")
public class UserPrivacySettingPo {
  @TableId
  @TableField("user_id")
  private Long userId;
  @TableField("allow_group_friend_request")
  private Boolean allowGroupFriendRequest;
  @TableField("hide_group_member_info")
  private Boolean hideGroupMemberInfo;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
