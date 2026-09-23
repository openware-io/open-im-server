package io.openware.im.conversation.infra.persistence.account;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.im.conversation.domain.account.port.UserConversationDataPurger;
import io.openware.im.conversation.infra.persistence.channel.mapper.ChannelSubscriptionMapper;
import io.openware.im.conversation.infra.persistence.channel.po.ChannelSubscriptionPo;
import io.openware.im.conversation.infra.persistence.group.mapper.ConversationMemberMapper;
import io.openware.im.conversation.infra.persistence.group.po.ConversationMemberPo;
import io.openware.im.conversation.infra.persistence.secretchat.mapper.SecretChatMapper;
import io.openware.im.conversation.infra.persistence.secretchat.po.SecretChatPo;
import io.openware.im.conversation.infra.persistence.secretgroupchat.mapper.SecretGroupMemberMapper;
import io.openware.im.conversation.infra.persistence.secretgroupchat.po.SecretGroupMemberPo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户会话数据硬删实现：移除群成员关系、删除其参与的私密会话与私密群聊成员关系、清理频道订阅。
 * 群/频道所有权（owner_id）随账号删除而悬空，属后续所有权转移/解散策略，本清理器不处理。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MybatisUserConversationDataPurger implements UserConversationDataPurger {
  private final ConversationMemberMapper conversationMemberMapper;
  private final SecretChatMapper secretChatMapper;
  private final SecretGroupMemberMapper secretGroupMemberMapper;
  private final ChannelSubscriptionMapper channelSubscriptionMapper;

  @Override
  public void purge(long userId) {
    conversationMemberMapper.delete(
        Wrappers.<ConversationMemberPo>lambdaQuery().eq(ConversationMemberPo::getUserId, userId));
    secretChatMapper.delete(Wrappers.<SecretChatPo>lambdaQuery()
        .eq(SecretChatPo::getUserA, userId).or().eq(SecretChatPo::getUserB, userId));
    secretGroupMemberMapper.delete(
        Wrappers.<SecretGroupMemberPo>lambdaQuery().eq(SecretGroupMemberPo::getUserId, userId));
    channelSubscriptionMapper.delete(
        Wrappers.<ChannelSubscriptionPo>lambdaQuery().eq(ChannelSubscriptionPo::getUserId, userId));
    log.info("Purged user conversation data, userId={}", userId);
  }
}
