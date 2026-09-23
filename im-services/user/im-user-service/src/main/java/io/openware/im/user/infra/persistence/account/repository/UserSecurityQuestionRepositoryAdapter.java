package io.openware.im.user.infra.persistence.account.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.im.user.domain.account.model.UserSecurityQuestion;
import io.openware.im.user.domain.account.repository.UserSecurityQuestionRepository;
import io.openware.im.user.infra.persistence.account.converter.UserSecurityQuestionPersistenceConverter;
import io.openware.im.user.infra.persistence.account.mapper.UserSecurityQuestionMapper;
import io.openware.im.user.infra.persistence.account.po.UserSecurityQuestionPo;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserSecurityQuestionRepositoryAdapter implements UserSecurityQuestionRepository {
  private final UserSecurityQuestionMapper mapper;

  @Override
  public Optional<UserSecurityQuestion> findByUserId(Long userId) {
    return Optional.ofNullable(mapper.selectOne(
        Wrappers.<UserSecurityQuestionPo>lambdaQuery().eq(UserSecurityQuestionPo::getUserId, userId)))
        .map(UserSecurityQuestionPersistenceConverter::toDomain);
  }

  @Override
  public UserSecurityQuestion save(UserSecurityQuestion entity) {
    UserSecurityQuestionPo po = UserSecurityQuestionPersistenceConverter.toPo(entity);
    if (po.getId() == null) {
      mapper.insert(po);
      entity.assignId(po.getId());
    } else {
      mapper.updateById(po);
    }
    return entity;
  }

  @Override
  public int deleteByUserId(Long userId) {
    return mapper.delete(Wrappers.<UserSecurityQuestionPo>lambdaQuery().eq(UserSecurityQuestionPo::getUserId, userId));
  }
}
