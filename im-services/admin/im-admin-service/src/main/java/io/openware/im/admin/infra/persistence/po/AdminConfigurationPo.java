package io.openware.im.admin.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("adm_system_config")
public class AdminConfigurationPo {
  @TableId(type = IdType.AUTO)
  private Integer id;
  @TableField("config_key")
  private String configKey;
  @TableField("config_value")
  private String configValue;
  @TableField("config_group")
  private String configGroup;
  private String description;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
