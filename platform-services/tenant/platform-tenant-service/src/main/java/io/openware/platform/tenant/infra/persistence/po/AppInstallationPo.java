package io.openware.platform.tenant.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("tnt_app_installation")
public class AppInstallationPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long tenantId;
  private String appId;
  private String clientType;
  private String status;
  private Long installedBy;
  private LocalDateTime installedAt;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;
}
