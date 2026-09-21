package com.gvchat.im.user.infra.persistence.notificationsetting.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user_notification_setting")
public class UserNotificationSettingPo {
  @TableId
  @TableField("user_id")
  private Long userId;
  @TableField("notify_private")
  private Boolean notifyPrivate;
  @TableField("notify_group")
  private Boolean notifyGroup;
  @TableField("notify_channel")
  private Boolean notifyChannel;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
