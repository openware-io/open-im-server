package io.openware.platform.tenant.infra.persistence.row;

import lombok.Getter;
import lombok.Setter;

/** iam_user_role 聚合出的角色行（内部只读投影，非 PO）。 */
@Getter
@Setter
public class IamRoleRow {
    private Long tenantId;
    private Long organizationId;
    private Long storeId;
    private String roleCode;
}
