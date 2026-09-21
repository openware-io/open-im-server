package com.gvchat.common.audit.infra.persistence.row;

import lombok.Getter;
import lombok.Setter;

/** 租户名称只读行（{@code tnt_tenant}），仅用于审计列表补 tenantName，不参与业务写入。 */
@Getter
@Setter
public class TenantNameRow {
    private Long id;
    private String name;
}
