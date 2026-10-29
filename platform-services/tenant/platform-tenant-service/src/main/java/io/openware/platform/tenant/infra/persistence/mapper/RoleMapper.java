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
    @Select("SELECT * FROM iam_role /* tenant-agnostic: 主键查询需包含全局预置角色 tenant_id=NULL */ WHERE id = #{id}")
    RolePo selectByIdIgnoringTenant(@Param("id") Long id);

    /**
     * 写路径的角色行锁：权限集合替换/单权限开关以角色行为串行点，避免“版本校验通过后、替换前”
     * 被另一个管理员插入修改。必须在事务内调用。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM iam_role /* tenant-agnostic: 写路径行锁 */ WHERE id = #{id} FOR UPDATE")
    RolePo selectByIdForUpdate(@Param("id") Long id);

    /** 预置角色 tenant_id 为 NULL，租户可分配目录必须同时包含预置角色和本租户自定义角色。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT * FROM iam_role /* tenant-agnostic: 预置 + 当前租户角色目录 */
        WHERE status = 'ACTIVE'
          AND scope_level IN ('TENANT', 'STORE')
          AND (tenant_id IS NULL OR tenant_id = #{tenantId})
        ORDER BY scope_level, code
        """)
    java.util.List<RolePo> selectAssignableByTenant(@Param("tenantId") Long tenantId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT * FROM iam_role /* tenant-agnostic: 平台角色目录，tenant_id 为空 */
        WHERE status = 'ACTIVE'
          AND scope_level = 'PLATFORM'
        ORDER BY code
        """)
    java.util.List<RolePo> selectPlatformRoles();

    /** 全局角色定义：预置角色 + 平台自定义角色（tenant_id IS NULL），用于权限目录矩阵。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT * FROM iam_role /* tenant-agnostic: 全局角色模板，tenant_id 为空 */
        WHERE status = 'ACTIVE'
          AND tenant_id IS NULL
        ORDER BY code
        """)
    java.util.List<RolePo> selectGlobalRoles();
}
