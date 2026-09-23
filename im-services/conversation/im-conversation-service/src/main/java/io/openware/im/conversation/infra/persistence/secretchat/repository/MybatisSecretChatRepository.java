package io.openware.im.conversation.infra.persistence.secretchat.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.im.conversation.domain.secretchat.model.SecretChat;
import io.openware.im.conversation.domain.secretchat.repository.SecretChatRepository;
import io.openware.im.conversation.infra.persistence.secretchat.mapper.SecretChatMapper;
import io.openware.im.conversation.infra.persistence.secretchat.po.SecretChatPo;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisSecretChatRepository implements SecretChatRepository {
  private final SecretChatMapper secretChatMapper;

  @Override
  public SecretChat save(SecretChat secretChat) {
    SecretChatPo po = toPo(secretChat);
    if (po.getId() == null) {
      secretChatMapper.insert(po);
    } else {
      secretChatMapper.updateById(po);
    }
    return toDomain(po);
  }

  @Override
  public Optional<SecretChat> findById(long id) {
    return Optional.ofNullable(secretChatMapper.selectById(id)).map(this::toDomain);
  }

  @Override
  public Optional<SecretChat> findBetween(long userA, long userB) {
    SecretChatPo po = secretChatMapper.selectOne(new LambdaQueryWrapper<SecretChatPo>()
        .eq(SecretChatPo::getUserA, userA).eq(SecretChatPo::getUserB, userB));
    return Optional.ofNullable(po).map(this::toDomain);
  }

  @Override
  public long countBetween(long userA, long userB) {
    return secretChatMapper.selectCount(new LambdaQueryWrapper<SecretChatPo>()
        .eq(SecretChatPo::getUserA, userA).eq(SecretChatPo::getUserB, userB));
  }

  @Override
  public List<SecretChat> findByParticipant(long userId) {
    return secretChatMapper.selectList(new LambdaQueryWrapper<SecretChatPo>()
        .and(w -> w.eq(SecretChatPo::getUserA, userId).or().eq(SecretChatPo::getUserB, userId)))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public void deleteById(long id) {
    secretChatMapper.deleteById(id);
  }

  private SecretChatPo toPo(SecretChat chat) {
    SecretChatPo po = new SecretChatPo();
    po.setId(chat.getId());
    po.setUserA(chat.getUserA());
    po.setUserB(chat.getUserB());
    po.setStatus(chat.getStatus());
    po.setSafeCode(chat.getSafeCode());
    po.setDestroyPolicy(chat.getDestroyPolicy());
    po.setUserAPublicKey(chat.getUserAPublicKey());
    po.setUserBPublicKey(chat.getUserBPublicKey());
    po.setHandshakeState(chat.getHandshakeState());
    po.setCreatedBy(chat.getCreatedBy());
    po.setCreatedAt(chat.getCreatedAt());
    po.setUpdatedBy(chat.getUpdatedBy());
    po.setUpdatedAt(chat.getUpdatedAt());
    return po;
  }

  private SecretChat toDomain(SecretChatPo po) {
    return SecretChat.restore(po.getId(), po.getUserA(), po.getUserB(), po.getStatus(), po.getSafeCode(),
        po.getDestroyPolicy(), po.getUserAPublicKey(), po.getUserBPublicKey(), po.getHandshakeState(),
        po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt());
  }
}
