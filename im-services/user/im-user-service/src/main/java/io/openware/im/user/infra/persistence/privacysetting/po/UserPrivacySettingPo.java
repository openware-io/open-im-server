package io.openware.im.user.infra.persistence.privacysetting.po;

import com.baomidou.mybatisplus.annotation.IdType;
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
  /** 基线重建后本表主键从业务键 user_id 改为自增 id，user_id 降为唯一键。 */
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("user_id")
  private Long userId;
  @TableField("allow_group_friend_request")
  private Boolean allowGroupFriendRequest;
  @TableField("hide_group_member_info")
  private Boolean hideGroupMemberInfo;
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
