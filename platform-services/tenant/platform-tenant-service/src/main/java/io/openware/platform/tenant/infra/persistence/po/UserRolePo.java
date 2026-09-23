package io.openware.platform.tenant.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("iam_user_role")
public class UserRolePo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private Long tenantId;
    private Long organizationId;
    private Long storeId;
    private Long roleId;
    private String scopeType;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private String status;
    private Integer authorizationVersion;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
