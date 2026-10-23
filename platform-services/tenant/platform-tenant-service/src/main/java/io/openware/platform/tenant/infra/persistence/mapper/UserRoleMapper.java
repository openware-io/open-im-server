package io.openware.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.tenant.infra.persistence.po.UserRolePo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserRoleMapper extends BaseMapper<UserRolePo> {

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE iam_user_role
        SET status = 'REVOKED', updated_at = NOW(3)
        WHERE account_id = #{accountId} AND role_id = #{roleId} AND status = 'ACTIVE'
        """)
    int revokeByAccountAndRole(@Param("accountId") Long accountId, @Param("roleId") Long roleId);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE iam_user_role
        SET organization_id = #{organizationId}, store_id = #{storeId}, scope_type = #{scopeType},
            updated_at = NOW(3)
        WHERE account_id = #{accountId} AND role_id = #{roleId} AND status = 'ACTIVE'
        """)
    int updateScopeByAccountAndRole(@Param("accountId") Long accountId, @Param("roleId") Long roleId,
                                    @Param("organizationId") Long organizationId, @Param("storeId") Long storeId,
                                    @Param("scopeType") String scopeType);
}
