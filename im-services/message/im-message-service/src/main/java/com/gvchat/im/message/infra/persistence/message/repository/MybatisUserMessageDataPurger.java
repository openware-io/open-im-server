package com.gvchat.im.message.infra.persistence.message.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gvchat.common.enums.ChatType;
import com.gvchat.im.message.domain.message.port.UserMessageDataPurger;
import com.gvchat.im.message.infra.persistence.message.mapper.MessageMapper;
import com.gvchat.im.message.infra.persistence.message.mapper.MessageReadStatusMapper;
import com.gvchat.im.message.infra.persistence.message.mapper.UserSyncIndexMapper;
import com.gvchat.im.message.infra.persistence.message.po.MessagePo;
import com.gvchat.im.message.infra.persistence.message.po.MessageReadStatusPo;
import com.gvchat.im.message.infra.persistence.message.po.UserSyncIndexPo;
import com.gvchat.im.message.infra.persistence.secretgroupmessage.mapper.SecretGroupMessageMapper;
import com.gvchat.im.message.infra.persistence.secretgroupmessage.po.SecretGroupMessagePo;
import com.gvchat.im.message.infra.persistence.secretmessage.mapper.SecretMessageMapper;
import com.gvchat.im.message.infra.persistence.secretmessage.po.SecretMessagePo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户消息数据硬删实现：删除该用户发送的消息、私密消息/私密群聊密文（发送方或接收方）、
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
    // 群/频道消息 tombstone：保留 (conversation_id, seq) 顺序游标，仅把正文替换为「该用户已注销」，
    // 避免物理删除破坏其他成员的消息序列连续性（对应注销清理 #12）。
    messageMapper.update(null, Wrappers.<MessagePo>lambdaUpdate()
        .eq(MessagePo::getFromUserId, userId)
        .in(MessagePo::getChatType, ChatType.GROUP, ChatType.CHANNEL)
        .set(MessagePo::getContent, "该用户已注销"));
    // 单聊消息：删除该用户参与的全部私聊（发送方 + 接收方两侧），单聊无群顺序游标问题。
    messageMapper.delete(Wrappers.<MessagePo>lambdaQuery()
        .eq(MessagePo::getChatType, ChatType.PRIVATE)
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
