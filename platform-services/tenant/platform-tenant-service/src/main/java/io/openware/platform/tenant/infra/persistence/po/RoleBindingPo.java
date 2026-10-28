package io.openware.platform.tenant.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("iam_role_binding")
public class RoleBindingPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String subjectType;
  private Long subjectId;
  private Long roleId;
  private String scopeType;
  private Long tenantId;
  private Long organizationId;
  private Long storeId;
  private Integer authorizationVersion;
  private String status;
  private LocalDateTime effectiveFrom;
  private LocalDateTime effectiveTo;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;
}
