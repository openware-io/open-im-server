package io.openware.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * IAM 权限目录版本查询。
 *
 * <p>只做一次聚合查询，不读业务行：版本变化的判据是全局角色/授权/权限字典的“数量 + 授权集合校验和 +
 * 最后更新时间”。追加授权集合 CRC 校验和，是为了覆盖“同一毫秒内先删除再插入、行数不变但集合已变”的
 * 场景，避免缓存版本键碰撞后继续返回旧矩阵。
 */
@Mapper
public interface IamCatalogVersionMapper {

  @InterceptorIgnore(tenantLine = "true")
  @Select("""
      SELECT CONCAT(
        (SELECT COUNT(*) FROM iam_role /* platform-wide: global role catalog version */
          WHERE status = 'ACTIVE' AND tenant_id IS NULL), ':',
        (SELECT COALESCE(DATE_FORMAT(MAX(updated_at), '%Y%m%d%H%i%s%f'), '0') FROM iam_role /* platform-wide */
          WHERE status = 'ACTIVE' AND tenant_id IS NULL), ':',
        (SELECT COUNT(*) FROM iam_role_permission rp
          JOIN iam_role r /* platform-wide */ ON r.id = rp.role_id
          WHERE r.status = 'ACTIVE' AND r.tenant_id IS NULL), ':',
        (SELECT COALESCE(BIT_XOR(CRC32(CONCAT(rp.role_id, ':', rp.permission_id))), 0)
          FROM iam_role_permission rp
          JOIN iam_role r /* platform-wide */ ON r.id = rp.role_id
          WHERE r.status = 'ACTIVE' AND r.tenant_id IS NULL), ':',
        (SELECT COALESCE(DATE_FORMAT(MAX(rp.updated_at), '%Y%m%d%H%i%s%f'), '0') FROM iam_role_permission rp
          JOIN iam_role r /* platform-wide */ ON r.id = rp.role_id
          WHERE r.status = 'ACTIVE' AND r.tenant_id IS NULL), ':',
        (SELECT COUNT(*) FROM iam_permission WHERE status = 'ACTIVE'), ':',
        (SELECT COALESCE(DATE_FORMAT(MAX(updated_at), '%Y%m%d%H%i%s%f'), '0') FROM iam_permission
          WHERE status = 'ACTIVE')
      ) AS version
      """)
  String selectVersion();

  /**
   * 某角色的权限集合版本。
   *
   * <p>角色行本身也可能改名/改作用域，故一并纳入角色 {@code updated_at}；授权集合用 CRC 校验和，
   * 覆盖同毫秒内删除/插入导致行数不变但集合变化的场景。
   */
  @InterceptorIgnore(tenantLine = "true")
  @Select("""
      SELECT CONCAT(
        (SELECT COALESCE(DATE_FORMAT(MAX(updated_at), '%Y%m%d%H%i%s%f'), '0') FROM iam_role /* tenant-agnostic: role id access-checked by caller */
          WHERE id = #{roleId}), ':',
        (SELECT COUNT(*) FROM iam_role_permission WHERE role_id = #{roleId}), ':',
        (SELECT COALESCE(BIT_XOR(CRC32(CONCAT(role_id, ':', permission_id))), 0)
          FROM iam_role_permission WHERE role_id = #{roleId}), ':',
        (SELECT COALESCE(DATE_FORMAT(MAX(updated_at), '%Y%m%d%H%i%s%f'), '0')
          FROM iam_role_permission WHERE role_id = #{roleId})
      ) AS version
      """)
  String selectRolePermissionVersion(@Param("roleId") Long roleId);
}
