package com.gvchat.im.message.infra.persistence.secretgroupmessage.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.im.message.domain.secretgroupmessage.model.SecretGroupMessage;
import com.gvchat.im.message.domain.secretgroupmessage.repository.SecretGroupMessageRepository;
import com.gvchat.im.message.infra.persistence.secretgroupmessage.mapper.SecretGroupMessageMapper;
import com.gvchat.im.message.infra.persistence.secretgroupmessage.po.SecretGroupMessagePo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisSecretGroupMessageRepository implements SecretGroupMessageRepository {
  private final SecretGroupMessageMapper mapper;

  @Override
  public SecretGroupMessage save(SecretGroupMessage message) {
    SecretGroupMessagePo po = toPo(message);
    if (po.getId() == null) {
      mapper.insert(po);
    } else {
      mapper.updateById(po);
    }
    return toDomain(po);
  }

  @Override
  public long nextSeq(long secretGroupId) {
    SecretGroupMessagePo last = mapper.selectOne(new LambdaQueryWrapper<SecretGroupMessagePo>()
        .eq(SecretGroupMessagePo::getSecretGroupId, secretGroupId)
        .orderByDesc(SecretGroupMessagePo::getSeq)
        .last("LIMIT 1"));
    return (last == null ? 0L : last.getSeq()) + 1;
  }

  @Override
  public List<SecretGroupMessage> listAfterSeq(long secretGroupId, long recipientUserId, long afterSeq, int limit) {
    int safeLimit = Math.min(limit <= 0 ? 50 : limit, 100);
    return mapper.selectList(new LambdaQueryWrapper<SecretGroupMessagePo>()
        .eq(SecretGroupMessagePo::getSecretGroupId, secretGroupId)
        .eq(SecretGroupMessagePo::getRecipientUserId, recipientUserId)
        .eq(SecretGroupMessagePo::getStatus, "active")
        .gt(SecretGroupMessagePo::getSeq, afterSeq)
        .orderByAsc(SecretGroupMessagePo::getSeq)
        .last("LIMIT " + safeLimit)).stream().map(this::toDomain).toList();
  }

  @Override
  public int deleteByMsgIdAndRecipient(long secretGroupId, String msgId, long recipientUserId) {
    return mapper.delete(new LambdaQueryWrapper<SecretGroupMessagePo>()
        .eq(SecretGroupMessagePo::getSecretGroupId, secretGroupId)
        .eq(SecretGroupMessagePo::getMsgId, msgId)
        .eq(SecretGroupMessagePo::getRecipientUserId, recipientUserId));
  }

  @Override
  public int deleteByMsgId(long secretGroupId, String msgId) {
    return mapper.delete(new LambdaQueryWrapper<SecretGroupMessagePo>()
        .eq(SecretGroupMessagePo::getSecretGroupId, secretGroupId)
        .eq(SecretGroupMessagePo::getMsgId, msgId));
  }

  @Override
  public Optional<SecretGroupMessage> findByMsgId(long secretGroupId, String msgId) {
    return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<SecretGroupMessagePo>()
        .eq(SecretGroupMessagePo::getSecretGroupId, secretGroupId)
        .eq(SecretGroupMessagePo::getMsgId, msgId)
        .last("LIMIT 1"))).map(this::toDomain);
  }

  @Override
  public List<SecretGroupMessage> findUncountedReadBy(long secretGroupId, long recipientUserId, long afterSeq,
      long viewerId) {
    return mapper.selectList(new LambdaQueryWrapper<SecretGroupMessagePo>()
        .eq(SecretGroupMessagePo::getSecretGroupId, secretGroupId)
        .eq(SecretGroupMessagePo::getRecipientUserId, recipientUserId)
        .le(SecretGroupMessagePo::getSeq, afterSeq)
        .ne(SecretGroupMessagePo::getFromUserId, viewerId)
        .eq(SecretGroupMessagePo::getStatus, "active")
        .isNull(SecretGroupMessagePo::getDestroyAt)
        .orderByAsc(SecretGroupMessagePo::getSeq)).stream().map(this::toDomain).toList();
  }

  @Override
  public LocalDateTime findEarliestDestroyAt(long secretGroupId, long recipientUserId) {
    SecretGroupMessagePo po = mapper.selectOne(new LambdaQueryWrapper<SecretGroupMessagePo>()
        .eq(SecretGroupMessagePo::getSecretGroupId, secretGroupId)
        .eq(SecretGroupMessagePo::getRecipientUserId, recipientUserId)
        .eq(SecretGroupMessagePo::getStatus, "active")
        .isNotNull(SecretGroupMessagePo::getDestroyAt)
        .orderByAsc(SecretGroupMessagePo::getDestroyAt)
        .last("LIMIT 1"));
    return po == null ? null : po.getDestroyAt();
  }

  @Override
  public List<SecretGroupMessage> findExpired(LocalDateTime now, int limit) {
    int safeLimit = Math.min(limit <= 0 ? 100 : limit, 500);
    return mapper.selectList(new LambdaQueryWrapper<SecretGroupMessagePo>()
        .eq(SecretGroupMessagePo::getStatus, "active")
        .isNotNull(SecretGroupMessagePo::getDestroyAt)
        .le(SecretGroupMessagePo::getDestroyAt, now)
        .orderByAsc(SecretGroupMessagePo::getDestroyAt)
        .last("LIMIT " + safeLimit)).stream().map(this::toDomain).toList();
  }

  private SecretGroupMessagePo toPo(SecretGroupMessage message) {
    SecretGroupMessagePo po = new SecretGroupMessagePo();
    po.setId(message.getId());
    po.setSecretGroupId(message.getSecretGroupId());
    po.setMsgId(message.getMsgId());
    po.setFromUserId(message.getFromUserId());
    po.setRecipientUserId(message.getRecipientUserId());
    po.setCiphertext(message.getCiphertext());
    po.setSeq(message.getSeq());
    po.setStatus(message.getStatus());
    po.setDestroyAt(message.getDestroyAt());
    po.setCreatedAt(message.getCreatedAt());
    return po;
  }

  private SecretGroupMessage toDomain(SecretGroupMessagePo po) {
    return SecretGroupMessage.restore(po.getId(), po.getSecretGroupId(), po.getMsgId(), po.getFromUserId(),
        po.getRecipientUserId(), po.getCiphertext(), po.getSeq(), po.getStatus(), po.getDestroyAt(), po.getCreatedAt());
  }
}
