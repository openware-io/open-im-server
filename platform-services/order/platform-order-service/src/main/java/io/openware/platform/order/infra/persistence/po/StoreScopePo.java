package io.openware.platform.order.infra.persistence.po;

import lombok.Getter;
import lombok.Setter;

/**
 * 门店作用域快照（只读投影，来自租户域 {@code tnt_store}）。
 *
 * <p>不是本服务拥有的表，也没有任何写路径：只用于跨门店库存写入时校验
 * {@code organization_id = store.organization_id} 与 {@code business_type = store.business_type}。
 * 列别名与字段名的下划线映射由 MyBatis 的 {@code map-underscore-to-camel-case} 完成。
 */
@Getter
@Setter
public class StoreScopePo {
    private Long tenantId;
    private Long storeId;
    private Long organizationId;
    private String businessType;
    private String status;
}
