package io.openware.im.user.infra.persistence.devicekey.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user_device_key")
public class DeviceKeyPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("user_id")
  private Long userId;
  @TableField("device_id")
  private String deviceId;
  @TableField("public_key")
  private String publicKey;
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
