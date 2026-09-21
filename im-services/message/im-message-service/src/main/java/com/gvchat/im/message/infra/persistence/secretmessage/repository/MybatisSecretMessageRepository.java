package com.gvchat.im.message.infra.persistence.secretmessage.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.im.message.domain.secretmessage.model.SecretMessage;
import com.gvchat.im.message.domain.secretmessage.repository.SecretMessageRepository;
import com.gvchat.im.message.infra.persistence.secretmessage.mapper.SecretMessageMapper;
import com.gvchat.im.message.infra.persistence.secretmessage.po.SecretMessagePo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisSecretMessageRepository implements SecretMessageRepository {
  private final SecretMessageMapper secretMessageMapper;

  @Override
  public SecretMessage save(SecretMessage message) {
    SecretMessagePo po = toPo(message);
    if (po.getId() == null) {
      secretMessageMapper.insert(po);
    } else {
      secretMessageMapper.updateById(po);
    }
    return toDomain(po);
  }

  @Override
  public long nextSeq(long secretChatId) {
    SecretMessagePo last = secretMessageMapper.selectOne(new LambdaQueryWrapper<SecretMessagePo>()
        .eq(SecretMessagePo::getSecretChatId, secretChatId)
        .orderByDesc(SecretMessagePo::getSeq)
        .last("LIMIT 1"));
    long base = last == null ? 0L : last.getSeq();
    return base + 1;
  }

  @Override
  public List<SecretMessage> listAfterSeq(long secretChatId, long afterSeq, int limit) {
    int safeLimit = Math.min(limit <= 0 ? 50 : limit, 100);
    return secretMessageMapper.selectList(new LambdaQueryWrapper<SecretMessagePo>()
        .eq(SecretMessagePo::getSecretChatId, secretChatId)
        .eq(SecretMessagePo::getStatus, "active")
        .gt(SecretMessagePo::getSeq, afterSeq)
        .orderByAsc(SecretMessagePo::getSeq)
        .last("LIMIT " + safeLimit)).stream().map(this::toDomain).toList();
  }

  @Override
  public Optional<SecretMessage> findByMsgId(long secretChatId, String msgId) {
    SecretMessagePo po = secretMessageMapper.selectOne(new LambdaQueryWrapper<SecretMessagePo>()
        .eq(SecretMessagePo::getSecretChatId, secretChatId)
        .eq(SecretMessagePo::getMsgId, msgId)
        .last("LIMIT 1"));
    return Optional.ofNullable(po).map(this::toDomain);
  }

  @Override
  public int deleteByMsgId(long secretChatId, String msgId) {
    return secretMessageMapper.delete(new LambdaQueryWrapper<SecretMessagePo>()
        .eq(SecretMessagePo::getSecretChatId, secretChatId)
        .eq(SecretMessagePo::getMsgId, msgId));
  }

  @Override
  public int deleteBySecretChatId(long secretChatId) {
    return secretMessageMapper.delete(new LambdaQueryWrapper<SecretMessagePo>()
        .eq(SecretMessagePo::getSecretChatId, secretChatId));
  }

  @Override
  public List<SecretMessage> findUncountedReadBy(long secretChatId, long afterSeq, long viewerId) {
    return secretMessageMapper.selectList(new LambdaQueryWrapper<SecretMessagePo>()
        .eq(SecretMessagePo::getSecretChatId, secretChatId)
        .le(SecretMessagePo::getSeq, afterSeq)
        .ne(SecretMessagePo::getFromUserId, viewerId)
        .eq(SecretMessagePo::getStatus, "active")
        .isNull(SecretMessagePo::getDestroyAt)
        .orderByAsc(SecretMessagePo::getSeq)).stream().map(this::toDomain).toList();
  }

  @Override
  public List<SecretMessage> findActiveWithoutDestroyAt(long secretChatId) {
    return secretMessageMapper.selectList(new LambdaQueryWrapper<SecretMessagePo>()
        .eq(SecretMessagePo::getSecretChatId, secretChatId)
        .eq(SecretMessagePo::getStatus, "active")
        .isNull(SecretMessagePo::getDestroyAt)
        .orderByAsc(SecretMessagePo::getSeq)).stream().map(this::toDomain).toList();
  }

  @Override
  public LocalDateTime findEarliestDestroyAt(long secretChatId) {
    SecretMessagePo po = secretMessageMapper.selectOne(new LambdaQueryWrapper<SecretMessagePo>()
        .eq(SecretMessagePo::getSecretChatId, secretChatId)
        .eq(SecretMessagePo::getStatus, "active")
        .isNotNull(SecretMessagePo::getDestroyAt)
        .orderByAsc(SecretMessagePo::getDestroyAt)
        .last("LIMIT 1"));
    return po == null ? null : po.getDestroyAt();
  }

  @Override
  public List<SecretMessage> findActiveByMsgIds(long secretChatId, List<String> msgIds, LocalDateTime now) {
    if (msgIds == null || msgIds.isEmpty()) {
      return List.of();
    }
    return secretMessageMapper.selectList(new LambdaQueryWrapper<SecretMessagePo>()
        .eq(SecretMessagePo::getSecretChatId, secretChatId)
        .in(SecretMessagePo::getMsgId, msgIds)
        .eq(SecretMessagePo::getStatus, "active")
        .isNotNull(SecretMessagePo::getDestroyAt)
        .le(SecretMessagePo::getDestroyAt, now)
        .orderByAsc(SecretMessagePo::getSeq)).stream().map(this::toDomain).toList();
  }

  @Override
  public List<SecretMessage> findExpired(LocalDateTime now, int limit) {
    int safeLimit = Math.min(limit <= 0 ? 100 : limit, 500);
    return secretMessageMapper.selectList(new LambdaQueryWrapper<SecretMessagePo>()
        .eq(SecretMessagePo::getStatus, "active")
        .le(SecretMessagePo::getDestroyAt, now)
        .isNotNull(SecretMessagePo::getDestroyAt)
        .orderByAsc(SecretMessagePo::getDestroyAt)
        .last("LIMIT " + safeLimit)).stream().map(this::toDomain).toList();
  }

  private SecretMessagePo toPo(SecretMessage message) {
    SecretMessagePo po = new SecretMessagePo();
    po.setId(message.getId());
    po.setSecretChatId(message.getSecretChatId());
    po.setMsgId(message.getMsgId());
    po.setFromUserId(message.getFromUserId());
    po.setCiphertext(message.getCiphertext());
    po.setSeq(message.getSeq());
    po.setStatus(message.getStatus());
    po.setDestroyAt(message.getDestroyAt());
    po.setCreatedBy(message.getCreatedBy());
    po.setCreatedAt(message.getCreatedAt());
    po.setUpdatedBy(message.getUpdatedBy());
    po.setUpdatedAt(message.getUpdatedAt());
    return po;
  }

  private SecretMessage toDomain(SecretMessagePo po) {
    return SecretMessage.restore(po.getId(), po.getSecretChatId(), po.getMsgId(), po.getFromUserId(),
        po.getCiphertext(), po.getSeq(), po.getStatus(), po.getDestroyAt(), po.getCreatedBy(),
        po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt());
  }
}
