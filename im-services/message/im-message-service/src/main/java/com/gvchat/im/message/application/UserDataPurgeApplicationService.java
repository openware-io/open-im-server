package com.gvchat.im.message.application;

import com.gvchat.im.message.domain.message.port.UserMessageDataPurger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户数据清理应用服务：账号被硬删除后清除该用户在消息服务的全部消息数据。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDataPurgeApplicationService {
  private final UserMessageDataPurger userMessageDataPurger;

  @Transactional
  public void purge(long userId) {
    userMessageDataPurger.purge(userId);
    log.info("Purged message data for deleted account, userId={}", userId);
  }
}
