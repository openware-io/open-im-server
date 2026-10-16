package io.openware.platform.tenant.domain.iam.repository;

import io.openware.platform.tenant.domain.iam.model.Permission;
import java.util.List;
import java.util.Optional;

/**
 * 权限点仓储端口（只读：权限点是随迁移发布的内置字典，运行时不新增）。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface PermissionRepository {

  /**
   * 按标识集合批量读取权限点。
   *
   * <p>返回的条目数可能少于入参：调用方据此判断"请求了不存在的权限"（授权校验必须拒绝，
   * 不能静默忽略未知标识）。
   */
  List<Permission> findByIds(List<Long> permissionIds);

  /** 按编码精确读取权限点（取一条）。 */
  Optional<Permission> findByCode(String code);

  /** 列出全部**可用**权限点。 */
  List<Permission> listActive();
}
