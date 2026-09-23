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
  ClientRelease save(ClientRelease release);
  boolean update(ClientRelease release, long expectedRowVersion);
  void replaceArtifacts(long releaseId, List<ReleaseArtifact> artifacts, long actorId, java.time.LocalDateTime timestamp);
}
