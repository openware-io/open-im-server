package io.openware.im.admin.domain.clientrelease.repository;

import io.openware.im.admin.domain.clientrelease.model.*;
import java.util.List;
import java.util.Optional;

public interface ClientReleasePolicyRepository {
  Optional<Persisted<ClientReleasePolicy>> find(ReleasePlatform platform, ReleaseChannel channel);

  List<Persisted<ClientReleasePolicy>> findAll();

  /** 新增；审计列由适配器按 {@code actorId/occurredAt} 填充。 */
  Persisted<ClientReleasePolicy> save(ClientReleasePolicy policy, long actorId, java.time.LocalDateTime occurredAt);

  /** 条件更新；只写 updated_*，created_* 保持不变。 */
  boolean update(ClientReleasePolicy policy, long expectedRowVersion, long actorId, java.time.LocalDateTime occurredAt);
}
