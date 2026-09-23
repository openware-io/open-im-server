package io.openware.im.admin.domain.repository;

import io.openware.im.admin.domain.configuration.AdminConfiguration;
import java.util.List;
import java.util.Optional;

public interface AdminConfigurationRepository {
  List<AdminConfiguration> findAll();

  Optional<AdminConfiguration> findByKey(String configKey);

  AdminConfiguration save(AdminConfiguration configuration);
}
