package io.openware.platform.tenant.domain.entitlement.repository;

import java.util.List;

/** 租户业态开通（总部门店可见性的来源）。 */
public interface BusinessEntitlementRepository {

  List<BusinessEntitlement> findActive(long tenantId);

  BusinessEntitlement find(long tenantId, String businessType);

  void activate(long tenantId, String businessType, long actorId);

  record BusinessEntitlement(Long id, String businessType, String status, String source) {
  }
}
