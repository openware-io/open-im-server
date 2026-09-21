package com.gvchat.im.admin.domain.clientrelease.repository;

import com.gvchat.im.admin.domain.clientrelease.model.ClientReleaseOperation;
import java.util.Optional;

public interface ClientReleaseOperationRepository {
  Optional<ClientReleaseOperation> findByIdempotencyKey(String idempotencyKey);

  ClientReleaseOperation save(ClientReleaseOperation operation);
}
