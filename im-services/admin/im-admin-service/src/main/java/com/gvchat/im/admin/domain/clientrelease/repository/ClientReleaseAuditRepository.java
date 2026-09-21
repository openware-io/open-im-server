package com.gvchat.im.admin.domain.clientrelease.repository;

import com.gvchat.im.admin.domain.clientrelease.model.ReleaseAuditLog;
import java.util.List;

public interface ClientReleaseAuditRepository {
  ReleaseAuditLog append(ReleaseAuditLog auditLog);

  List<ReleaseAuditLog> findByReleaseId(long releaseId, int offset, int size);

  long countByReleaseId(long releaseId);
}
