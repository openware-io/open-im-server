package io.openware.platform.tenant.infra.persistence.row;

import lombok.Getter;
import lombok.Setter;

/** iam_user_role 聚合出的角色绑定行（含角色名/作用域/门店名，内部只读投影，非 PO）。 */
@Getter
@Setter
public class IamRoleBindingRow {
    private Long roleId;
    private Long tenantId;
    private Long organizationId;
    private Long storeId;
    private String roleCode;
    private String roleName;
    private String scopeType;
    private String tenantName;
    private String storeName;
    private String status;
}
