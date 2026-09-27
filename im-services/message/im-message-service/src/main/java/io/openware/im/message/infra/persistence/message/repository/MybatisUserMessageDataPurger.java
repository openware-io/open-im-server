package io.openware.im.message.infra.persistence.message.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.im.message.domain.message.port.UserMessageDataPurger;
import io.openware.im.message.infra.persistence.message.mapper.MessageMapper;
import io.openware.im.message.infra.persistence.message.mapper.MessageReadStatusMapper;
import io.openware.im.message.infra.persistence.message.mapper.UserSyncIndexMapper;
import io.openware.im.message.infra.persistence.message.po.MessagePo;
import io.openware.im.message.infra.persistence.message.po.MessageReadStatusPo;
import io.openware.im.message.infra.persistence.message.po.UserSyncIndexPo;
import io.openware.im.message.infra.persistence.secretgroupmessage.mapper.SecretGroupMessageMapper;
import io.openware.im.message.infra.persistence.secretgroupmessage.po.SecretGroupMessagePo;
import io.openware.im.message.infra.persistence.secretmessage.mapper.SecretMessageMapper;
import io.openware.im.message.infra.persistence.secretmessage.po.SecretMessagePo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户消息数据硬删实现：删除该用户参与的全部消息、私密消息/私密群聊密文（发送方或接收方）、
 * 已读状态与同步索引/序列。私密销毁墓碑不含用户标识，随会话清理由会话服务兜底。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MybatisUserMessageDataPurger implements UserMessageDataPurger {
  private final MessageMapper messageMapper;
  private final MessageReadStatusMapper messageReadStatusMapper;
  private final UserSyncIndexMapper userSyncIndexMapper;
  private final SecretMessageMapper secretMessageMapper;
  private final SecretGroupMessageMapper secretGroupMessageMapper;

  @Override
  public void purge(long userId) {
    // 所有会话类型均物理删除；消息序号允许断号，避免为保序保留用户业务数据。
    messageMapper.delete(Wrappers.<MessagePo>lambdaQuery()
        .and(w -> w.eq(MessagePo::getFromUserId, userId)
            .or().eq(MessagePo::getToId, String.valueOf(userId))));
    messageReadStatusMapper.delete(
        Wrappers.<MessageReadStatusPo>lambdaQuery().eq(MessageReadStatusPo::getUserId, userId));
    userSyncIndexMapper.delete(
        Wrappers.<UserSyncIndexPo>lambdaQuery().eq(UserSyncIndexPo::getUserId, userId));
    userSyncIndexMapper.deleteSequence(userId);
    secretMessageMapper.delete(
        Wrappers.<SecretMessagePo>lambdaQuery().eq(SecretMessagePo::getFromUserId, userId));
    secretGroupMessageMapper.delete(Wrappers.<SecretGroupMessagePo>lambdaQuery()
        .eq(SecretGroupMessagePo::getFromUserId, userId)
        .or().eq(SecretGroupMessagePo::getRecipientUserId, userId));
    log.info("Purged user message data, userId={}", userId);
  }
}
