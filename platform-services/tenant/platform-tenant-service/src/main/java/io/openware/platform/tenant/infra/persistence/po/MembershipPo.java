package io.openware.platform.tenant.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("tnt_membership")
public class MembershipPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long accountId;
  private Long tenantId;
  private Long organizationId;
  private String membershipType;
  private String memberNo;
  private Integer authorizationVersion;
  private String status;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;
}
