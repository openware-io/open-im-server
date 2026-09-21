package com.gvchat.platform.tenant.infra.persistence.row;

import lombok.Getter;
import lombok.Setter;

/** iam_user_role 聚合出的经营上下文行（内部只读投影，非 PO）。 */
@Getter
@Setter
public class IamContextRow {
    private Long tenantId;
    private String tenantName;
    private Long organizationId;
    private String organizationName;
    private Long storeId;
    private String storeName;
    private String scopeType;
}
