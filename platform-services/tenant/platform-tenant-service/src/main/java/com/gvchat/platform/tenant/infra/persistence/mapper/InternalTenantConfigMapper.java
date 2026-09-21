package com.gvchat.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 租户级币种配置的**跨租户只读**查询。
 *
 * <p>上下文签发路径（identity / admin BFF 在 context select 时）**没有租户上下文**：
 * 它要读的正是「即将签发的那个目标租户」的币种，因此必须跳过 MyBatis 租户拦截器
 * （否则 {@code PlatformTenantLineHandler} 会因上下文缺失抛 IllegalStateException）。
 * 与 {@code IamSnapshotMapper} 的 {@code @InterceptorIgnore(tenantLine = "true")} 同款写法。
 *
 * <p>安全：调用方必须显式传入 tenantId；本 Mapper 只暴露单列只读查询，没有任何写操作。
 */
@Mapper
public interface InternalTenantConfigMapper {

    /** 租户级配置值（{@code store_id=0}）；无行时返回 null，由调用方按缺省 USD 解析。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT config_value FROM tnt_tenant_config
        WHERE tenant_id = #{tenantId} AND store_id = 0 AND config_key = #{configKey}
        ORDER BY id LIMIT 1
        """)
    String selectTenantConfigValue(@Param("tenantId") Long tenantId, @Param("configKey") String configKey);

    /**
     * 门店级配置值（{@code store_id = 指定门店}）；无行时返回 null，由调用方回退租户级配置/缺省值。
     *
     * <p>用途：KTV 营业时间这类「门店可不同、但有租户默认」的配置（{@code store_id=0} 是租户默认行，
     * 门店行覆盖它）。与租户级读取同款：跨租户只读、调用方必须显式传 tenantId。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT config_value FROM tnt_tenant_config
        WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND config_key = #{configKey}
        ORDER BY id LIMIT 1
        """)
    String selectStoreConfigValue(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
                                  @Param("configKey") String configKey);
}
