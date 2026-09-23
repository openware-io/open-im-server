package io.openware.im.user.infra.persistence.openplatform.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.im.user.domain.openplatform.model.OpenUserAuthorization;
import io.openware.im.user.domain.openplatform.repository.OpenUserAuthorizationRepository;
import io.openware.im.user.infra.persistence.openplatform.converter.OpenPlatformPersistenceConverter;
import io.openware.im.user.infra.persistence.openplatform.mapper.OpenUserAuthorizationMapper;
import io.openware.im.user.infra.persistence.openplatform.po.OpenUserAuthorizationPo;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class OpenUserAuthorizationRepositoryAdapter implements OpenUserAuthorizationRepository {
  private final OpenUserAuthorizationMapper mapper;

  @Override
  public Optional<OpenUserAuthorization> findByApplicationAndUser(Long applicationId, Long userId) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<OpenUserAuthorizationPo>lambdaQuery()
            .eq(OpenUserAuthorizationPo::getApplicationId, applicationId)
            .eq(OpenUserAuthorizationPo::getUserId, userId)))
        .map(OpenPlatformPersistenceConverter::toDomain);
  }

  @Override
  public List<OpenUserAuthorization> findByApplicationId(Long applicationId) {
    return mapper.selectList(Wrappers.<OpenUserAuthorizationPo>lambdaQuery()
            .eq(OpenUserAuthorizationPo::getApplicationId, applicationId)).stream()
        .map(OpenPlatformPersistenceConverter::toDomain).toList();
  }

  @Override
  public OpenUserAuthorization save(OpenUserAuthorization authorization) {
    OpenUserAuthorizationPo po = OpenPlatformPersistenceConverter.toPo(authorization);
    if (po.getId() == null) {
      mapper.insert(po);
      authorization.assignId(po.getId());
    } else {
      mapper.updateById(po);
    }
    return authorization;
  }
}
