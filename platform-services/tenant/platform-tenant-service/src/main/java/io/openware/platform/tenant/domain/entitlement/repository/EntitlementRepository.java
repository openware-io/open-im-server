package io.openware.platform.tenant.domain.entitlement.repository;

import java.util.List;

/** 租户商业权益。 */
public interface EntitlementRepository {

  List<Entitlement> findActive(long tenantId);

  void upsert(long tenantId, String featureCode, String status, String source);

  record Entitlement(String featureCode, String status, String source) {
  }
}
