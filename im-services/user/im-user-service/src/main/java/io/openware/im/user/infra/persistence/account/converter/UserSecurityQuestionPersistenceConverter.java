package io.openware.im.user.infra.persistence.account.converter;

import io.openware.im.user.domain.account.model.UserSecurityQuestion;
import io.openware.im.user.infra.persistence.account.po.UserSecurityQuestionPo;

public final class UserSecurityQuestionPersistenceConverter {
  private UserSecurityQuestionPersistenceConverter() {
  }

  public static UserSecurityQuestion toDomain(UserSecurityQuestionPo po) {
    UserSecurityQuestion entity = new UserSecurityQuestion();
    entity.restore(po.getId(), po.getUserId(), po.getQuestion(), po.getAnswerHash());
    return entity;
  }

  public static UserSecurityQuestionPo toPo(UserSecurityQuestion entity) {
    UserSecurityQuestionPo po = new UserSecurityQuestionPo();
    po.setId(entity.getId());
    po.setUserId(entity.getUserId());
    po.setQuestion(entity.getQuestion());
    po.setAnswerHash(entity.getAnswerHash());
    return po;
  }
}
