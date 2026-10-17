package io.openware.im.admin.domain.clientrelease.repository;

import io.openware.im.admin.domain.clientrelease.model.*;
import java.util.List;
import java.util.Optional;

public interface ClientReleaseRepository {
  Optional<ClientRelease> findById(Long id);
  List<ClientRelease> findAll(ReleasePlatform platform, ReleaseChannel channel, ReleaseStatus status, int offset, int size);
  long count(ReleasePlatform platform, ReleaseChannel channel, ReleaseStatus status);
  List<ClientRelease> findEligible(ReleasePlatform platform, ReleaseChannel channel, TargetArchitecture architecture);
  List<ClientRelease> findScheduledDue(java.time.LocalDateTime now, int size);
  long maxBuildNumber(ReleasePlatform platform, ReleaseChannel channel);

  /** 新增；审计列由适配器按 {@code actorId/occurredAt} 填充。 */
  ClientRelease save(ClientRelease release, long actorId, java.time.LocalDateTime occurredAt);

  /** 条件更新；只写 updated_*，created_* 保持不变。 */
  boolean update(ClientRelease release, long expectedRowVersion, long actorId, java.time.LocalDateTime occurredAt);

  void replaceArtifacts(long releaseId, List<ReleaseArtifact> artifacts, long actorId, java.time.LocalDateTime timestamp);
}
