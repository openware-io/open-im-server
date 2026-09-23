package io.openware.im.conversation.application.account;

import io.openware.im.conversation.domain.account.port.UserConversationDataPurger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户会话数据清理应用服务：账号被硬删除后清除该用户在会话服务的全部会话数据。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserConversationDataPurgeApplicationService {
  private final UserConversationDataPurger userConversationDataPurger;

  @Transactional
  public void purge(long userId) {
    userConversationDataPurger.purge(userId);
    log.info("Purged conversation data for deleted account, userId={}", userId);
  }
}
