package io.openware.platform.order.infra.persistence.mapper;

import io.openware.platform.order.infra.persistence.po.StoreScopePo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 门店作用域窄读 Mapper（只读租户域 {@code tnt_store} 的组织/业态快照）。
 *
 * <p>不是库存表，也没有任何写路径：只用于跨门店调拨时校验目标门店的
 * {@code organization_id = store.organization_id} 与 {@code business_type = store.business_type}。
 * 租户隔离由 MyBatis 租户拦截器自动附加 {@code tenant_id} 条件。
 */
@Mapper
public interface StoreScopeMapper {

    @Select("SELECT tenant_id, id AS store_id, organization_id, business_type, status FROM tnt_store "
            + "WHERE tenant_id = #{tenantId} AND id = #{storeId} LIMIT 1")
    StoreScopePo selectScope(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId);
}
