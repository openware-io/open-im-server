package io.openware.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.tenant.infra.persistence.po.RolePo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RoleMapper extends BaseMapper<RolePo> {

    /**
     * 按主键查角色并跳过租户行级过滤：预设角色 tenant_id 为 NULL，属于全局目录，
     * 不能被当前租户上下文过滤掉，否则授权校验会误判角色不存在。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM iam_role WHERE id = #{id}")
    RolePo selectByIdIgnoringTenant(@Param("id") Long id);

    /** 预置角色 tenant_id 为 NULL，租户可分配目录必须同时包含预置角色和本租户自定义角色。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT * FROM iam_role
        WHERE status = 'ACTIVE'
          AND scope_level IN ('TENANT', 'STORE')
          AND (tenant_id IS NULL OR tenant_id = #{tenantId})
        ORDER BY scope_level, code
        """)
    java.util.List<RolePo> selectAssignableByTenant(@Param("tenantId") Long tenantId);
}
