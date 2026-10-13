package io.openware.im.user.infra.persistence.notificationsetting.po;

import com.baomidou.mybatisplus.annotation.IdType;
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
  /** 基线重建后本表主键从业务键 user_id 改为自增 id，user_id 降为唯一键。 */
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("user_id")
  private Long userId;
  @TableField("notify_private")
  private Boolean notifyPrivate;
  @TableField("notify_group")
  private Boolean notifyGroup;
  @TableField("notify_channel")
  private Boolean notifyChannel;
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
