package com.gvchat.im.user.infra.persistence.device.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gvchat.common.enums.ClientPlatform;
import com.gvchat.common.enums.PushProvider;
import com.gvchat.im.user.infra.persistence.device.typehandler.ClientPlatformTypeHandler;
import com.gvchat.im.user.infra.persistence.device.typehandler.PushProviderTypeHandler;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName(value = "user_device_token", autoResultMap = true)
public class DeviceTokenPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("user_id")
  private Long userId;
  private String token;
  @TableField(value = "push_provider", typeHandler = PushProviderTypeHandler.class)
  private PushProvider pushProvider;
  @TableField(typeHandler = ClientPlatformTypeHandler.class)
  private ClientPlatform platform;
  @TableField("device_id")
  private String deviceId;
  private Boolean enabled;
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
