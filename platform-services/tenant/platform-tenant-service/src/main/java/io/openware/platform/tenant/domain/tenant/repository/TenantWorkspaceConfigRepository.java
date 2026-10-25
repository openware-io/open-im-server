package io.openware.platform.tenant.domain.tenant.repository;

/** 租户门店工作区模式配置（tenant 级）。 */
public interface TenantWorkspaceConfigRepository {

  String findStoreWorkspaceMode(long tenantId);

  void saveStoreWorkspaceMode(long tenantId, String mode, long actorId);
}
