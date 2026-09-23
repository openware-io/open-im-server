package io.openware.im.conversation.infra.persistence.secretgroupchat.repository;

import io.openware.im.conversation.domain.secretgroupchat.model.SecretGroupChat;
import io.openware.im.conversation.domain.secretgroupchat.repository.SecretGroupChatRepository;
import io.openware.im.conversation.infra.persistence.secretgroupchat.mapper.SecretGroupChatMapper;
import io.openware.im.conversation.infra.persistence.secretgroupchat.po.SecretGroupChatPo;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisSecretGroupChatRepository implements SecretGroupChatRepository {
  private final SecretGroupChatMapper mapper;

  @Override
  public SecretGroupChat save(SecretGroupChat group) {
    SecretGroupChatPo po = toPo(group);
    if (po.getId() == null) {
      mapper.insert(po);
    } else {
      mapper.updateById(po);
    }
    return toDomain(po);
  }

  @Override
  public Optional<SecretGroupChat> findById(long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
  }

  @Override
  public Optional<SecretGroupChat> findByInviteToken(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(mapper.selectOne(
        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SecretGroupChatPo>()
            .eq(SecretGroupChatPo::getInviteToken, token))).map(this::toDomain);
  }

  @Override
  public List<SecretGroupChat> findByIds(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return mapper.selectByIds(ids).stream().map(this::toDomain).toList();
  }

  @Override
  public void deleteById(long id) {
    mapper.deleteById(id);
  }

  private SecretGroupChatPo toPo(SecretGroupChat group) {
    SecretGroupChatPo po = new SecretGroupChatPo();
    po.setId(group.getId());
    po.setOwnerUserId(group.getOwnerUserId());
    po.setStatus(group.getStatus());
    po.setName(group.getName());
    po.setAnnouncement(group.getAnnouncement());
    po.setSafeCode(group.getSafeCode());
    po.setDestroyPolicy(group.getDestroyPolicy());
    po.setAnonymousEnabled(group.isAnonymousEnabled());
    po.setPinnedMsgId(group.getPinnedMsgId());
    po.setPinnedAt(group.getPinnedAt());
    po.setInviteToken(group.getInviteToken());
    po.setInviteExpiresAt(group.getInviteExpiresAt());
    po.setOwnerOnlyPost(group.isOwnerOnlyPost());
    po.setCreatedBy(group.getCreatedBy());
    po.setCreatedAt(group.getCreatedAt());
    po.setUpdatedBy(group.getUpdatedBy());
    po.setUpdatedAt(group.getUpdatedAt());
    return po;
  }

  private SecretGroupChat toDomain(SecretGroupChatPo po) {
    return SecretGroupChat.restore(po.getId(), po.getOwnerUserId(), po.getStatus(), po.getName(), po.getAnnouncement(),
        po.getSafeCode(), po.getDestroyPolicy(), Boolean.TRUE.equals(po.getAnonymousEnabled()), po.getPinnedMsgId(),
        po.getPinnedAt(), po.getInviteToken(), po.getInviteExpiresAt(), Boolean.TRUE.equals(po.getOwnerOnlyPost()),
        po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt());
  }
}
