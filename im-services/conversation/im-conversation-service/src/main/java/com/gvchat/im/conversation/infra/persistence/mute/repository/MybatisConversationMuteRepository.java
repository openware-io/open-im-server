package com.gvchat.im.conversation.infra.persistence.mute.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gvchat.im.conversation.domain.mute.model.ConversationMute;
import com.gvchat.im.conversation.domain.mute.repository.ConversationMuteRepository;
import com.gvchat.im.conversation.infra.persistence.mute.mapper.ConversationMuteMapper;
import com.gvchat.im.conversation.infra.persistence.mute.po.ConversationMutePo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisConversationMuteRepository implements ConversationMuteRepository {
  private final ConversationMuteMapper mapper;

  @Override
  public boolean existsByUserIdAndConversationId(long userId, String conversationId) {
    return mapper.selectCount(Wrappers.<ConversationMutePo>lambdaQuery()
        .eq(ConversationMutePo::getUserId, userId)
        .eq(ConversationMutePo::getConversationId, conversationId)) > 0;
  }

  @Override
  public List<ConversationMute> findByUserId(long userId) {
    return mapper.selectList(Wrappers.<ConversationMutePo>lambdaQuery().eq(ConversationMutePo::getUserId, userId))
        .stream().map(this::fromPo).toList();
  }

  @Override
  public ConversationMute save(ConversationMute mute) {
    ConversationMutePo po = new ConversationMutePo();
    po.setUserId(mute.userId());
    po.setConversationId(mute.conversationId());
    po.setCreatedAt(mute.createdAt());
    mapper.insert(po);
    return new ConversationMute(po.getId(), po.getUserId(), po.getConversationId(), po.getCreatedAt());
  }

  @Override
  public void deleteByUserIdAndConversationId(long userId, String conversationId) {
    mapper.delete(Wrappers.<ConversationMutePo>lambdaQuery()
        .eq(ConversationMutePo::getUserId, userId)
        .eq(ConversationMutePo::getConversationId, conversationId));
  }

  private ConversationMute fromPo(ConversationMutePo po) {
    return new ConversationMute(po.getId(), po.getUserId(), po.getConversationId(), po.getCreatedAt());
  }
}
