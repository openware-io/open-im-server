package com.gvchat.im.user.domain.account.repository;

import com.gvchat.im.user.domain.account.model.UserSecurityQuestion;
import java.util.Optional;

/** 用户密保问题仓储契约：每个用户至多一条记录。 */
public interface UserSecurityQuestionRepository {
  Optional<UserSecurityQuestion> findByUserId(Long userId);

  UserSecurityQuestion save(UserSecurityQuestion entity);

  /** 硬删指定用户的密保问题（账号注销清理用）。 */
  int deleteByUserId(Long userId);
}
