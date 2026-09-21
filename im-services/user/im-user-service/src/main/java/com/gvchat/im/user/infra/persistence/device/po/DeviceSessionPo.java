package com.gvchat.im.user.infra.persistence.device.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user_device_session")
public class DeviceSessionPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("user_id")
  private Long userId;
  @TableField("device_id")
  private String deviceId;
  @TableField("device_type")
  private String deviceType;
  @TableField("device_name")
  private String deviceName;
  @TableField("login_ip")
  private String loginIp;
  @TableField("login_method")
  private String loginMethod;
  @TableField("last_active_at")
  private LocalDateTime lastActiveAt;
  @TableField("last_active_ip")
  private String lastActiveIp;
  private String status;
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
