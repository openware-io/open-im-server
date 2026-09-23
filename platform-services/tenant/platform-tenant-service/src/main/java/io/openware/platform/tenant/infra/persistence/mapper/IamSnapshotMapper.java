package io.openware.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.openware.platform.tenant.infra.persistence.row.IamContextRow;
import io.openware.platform.tenant.infra.persistence.row.IamRoleBindingRow;
import io.openware.platform.tenant.infra.persistence.row.IamRoleRow;
import io.openware.platform.tenant.infra.persistence.row.MaskingRoleRow;
import io.openware.platform.tenant.infra.persistence.row.ConsumerApplicationRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * IAM 只读快照聚合查询（内部端点跨租户查询，跳过 MyBatis 租户拦截器）。
 */
@Mapper
public interface IamSnapshotMapper {

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT a.app_id, a.tenant_id, t.name AS tenant_name,
               a.organization_id, o.name AS organization_name,
               a.store_id, s.name AS store_name,
               a.permissions_json,
               GREATEST(a.authorization_version, ua.authorization_version) AS authorization_version
          FROM iam_consumer_application a
          JOIN tnt_tenant t ON t.id = a.tenant_id AND t.status = 'ACTIVE'
          LEFT JOIN tnt_organization o ON o.id = a.organization_id AND o.status = 'ACTIVE'
          LEFT JOIN tnt_store s ON s.id = a.store_id AND s.status = 'ACTIVE'
         JOIN tnt_app_user_authorization ua
           ON ua.app_id = a.app_id AND ua.account_id = #{accountId}
          AND ua.status = 'ACTIVE' AND ua.revoked_at IS NULL
         WHERE a.app_id = #{appId} AND a.status = 'ACTIVE'
        """)
    ConsumerApplicationRow selectConsumerApplication(@Param("appId") String appId, @Param("accountId") Long accountId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT COUNT(1) FROM tnt_app_user_authorization
        WHERE app_id = #{appId} AND account_id = #{accountId}
          AND status = 'ACTIVE'
          AND (revoked_at IS NULL)
        """)
    int countConsumerAuthorization(@Param("appId") String appId, @Param("accountId") Long accountId);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        INSERT INTO tnt_app_user_authorization
          (app_id, account_id, tenant_id, scope, status, authorization_version, authorized_at, created_at, updated_at)
        SELECT app_id, #{accountId}, tenant_id, #{scope}, 'ACTIVE', authorization_version, NOW(3), NOW(3), NOW(3)
        FROM iam_consumer_application WHERE app_id = #{appId} AND status = 'ACTIVE'
        ON DUPLICATE KEY UPDATE scope = VALUES(scope), status = 'ACTIVE',
          authorization_version = tnt_app_user_authorization.authorization_version + 1,
          authorized_at = NOW(3), revoked_at = NULL, updated_at = NOW(3)
        """)
    int grantConsumerAuthorization(@Param("appId") String appId, @Param("accountId") Long accountId,
                                  @Param("scope") String scope);

    /** 账号在作用域下的权限码列表（iam_user_role → iam_role → iam_permission）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT DISTINCT p.code
        FROM iam_user_role ur
        JOIN iam_role r ON r.id = ur.role_id AND r.status = 'ACTIVE'
        JOIN iam_role_permission rp ON rp.role_id = r.id
        JOIN iam_permission p ON p.id = rp.permission_id AND p.status = 'ACTIVE'
        WHERE ur.account_id = #{accountId}
          AND ur.status = 'ACTIVE'
          AND (ur.effective_from IS NULL OR ur.effective_from <= NOW(3))
          AND (ur.effective_to IS NULL OR ur.effective_to >= NOW(3))
          AND (
            ur.scope_type = 'PLATFORM'
            OR (
              ur.tenant_id = #{tenantId}
              AND (#{organizationId} IS NULL OR ur.organization_id IS NULL OR ur.organization_id = #{organizationId})
              AND (#{storeId} IS NULL OR ur.store_id IS NULL OR ur.store_id = #{storeId})
            )
          )
        """)
    List<String> selectPermissionCodes(@Param("accountId") Long accountId,
                                       @Param("tenantId") Long tenantId,
                                       @Param("organizationId") Long organizationId,
                                       @Param("storeId") Long storeId);

    /** 账号在作用域下的授权版本（取该作用域匹配的用户角色分配版本最大值）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT MAX(ur.authorization_version)
        FROM iam_user_role ur
        WHERE ur.account_id = #{accountId}
          AND ur.status = 'ACTIVE'
          AND (ur.effective_from IS NULL OR ur.effective_from <= NOW(3))
          AND (ur.effective_to IS NULL OR ur.effective_to >= NOW(3))
          AND (
            ur.scope_type = 'PLATFORM'
            OR (
              ur.tenant_id = #{tenantId}
              AND (#{organizationId} IS NULL OR ur.organization_id IS NULL OR ur.organization_id = #{organizationId})
              AND (#{storeId} IS NULL OR ur.store_id IS NULL OR ur.store_id = #{storeId})
            )
          )
        """)
    Integer selectAuthorizationVersion(@Param("accountId") Long accountId,
                                       @Param("tenantId") Long tenantId,
                                       @Param("organizationId") Long organizationId,
                                       @Param("storeId") Long storeId);

    /** 账号可访问的经营上下文（租户/组织/门店）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT DISTINCT ur.tenant_id, t.name AS tenant_name,
               ur.organization_id, o.name AS organization_name,
               ur.store_id, s.name AS store_name, ur.scope_type
        FROM iam_user_role ur
        JOIN tnt_tenant t ON t.id = ur.tenant_id
        LEFT JOIN tnt_organization o ON o.id = ur.organization_id
        LEFT JOIN tnt_store s ON s.id = ur.store_id
        WHERE ur.account_id = #{accountId}
          AND ur.status = 'ACTIVE'
          AND (ur.effective_from IS NULL OR ur.effective_from <= NOW(3))
          AND (ur.effective_to IS NULL OR ur.effective_to >= NOW(3))
        """)
    List<IamContextRow> selectContexts(@Param("accountId") Long accountId);

    /** 账号在各作用域下的角色编码。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT DISTINCT ur.tenant_id, ur.organization_id, ur.store_id, r.code AS role_code
        FROM iam_user_role ur
        JOIN iam_role r ON r.id = ur.role_id AND r.status = 'ACTIVE'
        WHERE ur.account_id = #{accountId}
          AND ur.status = 'ACTIVE'
          AND (ur.effective_from IS NULL OR ur.effective_from <= NOW(3))
          AND (ur.effective_to IS NULL OR ur.effective_to >= NOW(3))
        """)
    List<IamRoleRow> selectRoles(@Param("accountId") Long accountId);

    /** 账号是否拥有平台级角色（scope_type=PLATFORM）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT COUNT(1)
        FROM iam_user_role ur
        WHERE ur.account_id = #{accountId}
          AND ur.status = 'ACTIVE'
          AND ur.scope_type = 'PLATFORM'
          AND (ur.effective_from IS NULL OR ur.effective_from <= NOW(3))
          AND (ur.effective_to IS NULL OR ur.effective_to >= NOW(3))
        """)
    int countPlatformRoles(@Param("accountId") Long accountId);

    /** 全部活跃租户（平台管理员可访问所有租户）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT t.id AS tenant_id, t.name AS tenant_name,
               NULL AS organization_id, NULL AS organization_name,
               NULL AS store_id, NULL AS store_name, 'PLATFORM' AS scope_type
        FROM tnt_tenant t
        WHERE t.status = 'ACTIVE'
        ORDER BY t.id
        """)
    List<IamContextRow> selectAllTenantContexts();

    /**
     * 租户（可按组织收敛）下的全部启用门店，用于把租户/组织级授权展开为可选的门店上下文。
     * 库存、开台、订单等门店维度接口要求上下文带 storeId，只有租户级绑定时无法进入这些页面。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT s.tenant_id, t.name AS tenant_name,
               s.organization_id, o.name AS organization_name,
               s.id AS store_id, s.name AS store_name
        FROM tnt_store s
        JOIN tnt_tenant t ON t.id = s.tenant_id AND t.status = 'ACTIVE'
        LEFT JOIN tnt_organization o ON o.id = s.organization_id AND o.status = 'ACTIVE'
        WHERE s.status = 'ACTIVE'
          AND s.tenant_id = #{tenantId}
          AND (#{organizationId} IS NULL OR s.organization_id = #{organizationId})
        ORDER BY s.id
        """)
    List<IamContextRow> selectActiveStores(@Param("tenantId") Long tenantId,
                                           @Param("organizationId") Long organizationId);

    /** 脱敏权限管理：预置角色及其 member.pii.view 状态（跨租户只读，跳过租户拦截器）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT r.id AS role_id, r.code, r.name,
               CASE WHEN EXISTS (
                   SELECT 1 FROM iam_role_permission rp
                   JOIN iam_permission p ON p.id = rp.permission_id AND p.status = 'ACTIVE'
                   WHERE rp.role_id = r.id AND p.code = 'member.pii.view'
               ) THEN 1 ELSE 0 END AS pii_view
        FROM iam_role r
        WHERE r.tenant_id IS NULL AND r.role_type = 'PRESET' AND r.status = 'ACTIVE'
        ORDER BY r.id
        """)
    List<MaskingRoleRow> selectMaskingRoles();

    /** 账号的角色绑定视图（角色名 + 作用域 + 租户/门店名，含状态；跳过租户拦截器）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT ur.role_id AS role_id, ur.tenant_id AS tenant_id, ur.organization_id, ur.store_id,
               r.code AS role_code, r.name AS role_name,
               ur.scope_type AS scope_type, t.name AS tenant_name, s.name AS store_name,
               ur.status AS status
        FROM iam_user_role ur
        JOIN iam_role r ON r.id = ur.role_id
        LEFT JOIN tnt_tenant t ON t.id = ur.tenant_id
        LEFT JOIN tnt_store s ON s.id = ur.store_id
        WHERE ur.account_id = #{accountId}
        ORDER BY ur.id
        """)
    List<IamRoleBindingRow> selectRoleBindings(@Param("accountId") Long accountId);

    /** 账号的 ACTIVE iam_user_role id 列表（删除运营人员时逐个撤销用；跳过租户拦截器）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT id FROM iam_user_role
        WHERE account_id = #{accountId} AND status = 'ACTIVE'
        """)
    List<Long> selectUserRoleIds(@Param("accountId") Long accountId);

    /** 撤销角色绑定：iam_user_role 状态软删为 REVOKED（跳过租户拦截器）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE iam_user_role SET status = 'REVOKED', updated_at = NOW(3)
        WHERE id = #{userRoleId}
        """)
    int revokeUserRole(@Param("userRoleId") Long userRoleId);

    /** 查 iam_user_role 绑定的 account_id（撤销后逐出权限快照缓存用；跳过租户拦截器）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT account_id FROM iam_user_role WHERE id = #{userRoleId}
        """)
    Long selectAccountIdByUserRole(@Param("userRoleId") Long userRoleId);
}
