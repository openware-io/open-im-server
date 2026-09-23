package io.openware.im.admin.domain.clientrelease.repository;
import io.openware.im.admin.domain.clientrelease.model.*;
import java.util.List;
import java.util.Optional;
public interface ClientReleasePolicyRepository { Optional<ClientReleasePolicy> find(ReleasePlatform platform, ReleaseChannel channel); List<ClientReleasePolicy> findAll(); ClientReleasePolicy save(ClientReleasePolicy policy); boolean update(ClientReleasePolicy policy, long expectedRowVersion); }
