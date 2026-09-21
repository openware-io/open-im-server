package com.gvchat.platform.tenant.infra.persistence.row;

import lombok.Getter;
import lombok.Setter;

/** 脱敏权限管理：预置角色及其 member.pii.view 状态（内部只读投影）。 */
@Getter
@Setter
public class MaskingRoleRow {
    private Long roleId;
    private String code;
    private String name;
    private Boolean piiView;
}
