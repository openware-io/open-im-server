package io.openware.platform.admin.domain.role.repository;

import java.util.List;

/** 平台角色工作台模板。 */
public interface AdminRoleProfileRepository {

  List<RoleProfile> findByRoleCodes(List<String> roleCodes);

  List<RoleProfile> findAll();

  RoleProfile findById(Long id);

  void update(RoleProfile profile);

  record RoleProfile(Long id, String roleCode, String workspaceCode, String landingRoute,
                     String defaultScope, String defaultBusinessType, String navProfile,
                     Integer sortNo, String status) {
  }
}
