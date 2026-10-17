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
    // 审计列由适配器确定（standards/10 L299）：操作人恒为实体所属用户。
    java.time.LocalDateTime now = java.time.LocalDateTime.now();
    po.setCreatedBy(po.getUserId());
    po.setUpdatedBy(po.getUserId());
    if (po.getId() == null) {
      po.setCreatedAt(now);
      po.setUpdatedAt(now);
      mapper.insert(po);
      entity.assignId(po.getId());
    } else {
      po.setUpdatedAt(now);
      mapper.updateById(po);
    }
    return entity;
  }

  @Override
  public int deleteByUserId(Long userId) {
    return mapper.delete(Wrappers.<UserSecurityQuestionPo>lambdaQuery().eq(UserSecurityQuestionPo::getUserId, userId));
  }
}
