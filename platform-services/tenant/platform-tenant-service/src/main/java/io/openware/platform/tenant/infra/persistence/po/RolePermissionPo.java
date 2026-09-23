package io.openware.platform.tenant.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("iam_role_permission")
public class RolePermissionPo {
    private Long roleId;
    private Long permissionId;
}
