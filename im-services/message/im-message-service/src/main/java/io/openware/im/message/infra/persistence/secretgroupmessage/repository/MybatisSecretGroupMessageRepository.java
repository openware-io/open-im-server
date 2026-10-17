package io.openware.im.message.infra.persistence.secretgroupmessage.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.im.message.domain.secretgroupmessage.model.SecretGroupMessage;
import io.openware.im.message.domain.secretgroupmessage.repository.AuditSnapshot;
import io.openware.im.message.domain.secretgroupmessage.repository.Persisted;
import io.openware.im.message.domain.secretgroupmessage.repository.SecretGroupMessageRepository;
import io.openware.im.message.infra.persistence.secretgroupmessage.mapper.SecretGroupMessageMapper;
import io.openware.im.message.infra.persistence.secretgroupmessage.po.SecretGroupMessagePo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 私密群聊消息仓储实现。
 *
 * <p><b>投递时刻在本层确定</b>（standards/10 §聚合装配与持久化契约）：领域模型不带 {@code createdAt}，
 * 写库时由本层打时间戳、读库时经 {@link AuditSnapshot} 单独带出。
 *
 * <p><b>更新的列行为</b>：{@code updateById} 使用 NOT_NULL 策略，本层只在新建时写 {@code createdAt}；
 * 更新（安排销毁时刻）时保持 null 即不参与 SET，投递时刻不会被改写，也不需要额外一次 SELECT。
 */
@Component
@RequiredArgsConstructor
public class MybatisSecretGroupMessageRepository implements SecretGroupMessageRepository {
  private final SecretGroupMessageMapper mapper;

  @Override
  public Persisted<SecretGroupMessage> save(SecretGroupMessage message) {
    SecretGroupMessagePo po = toPo(message);
    if (po.getId() == null) {
      po.setCreatedAt(LocalDateTime.now());
      po.setUpdatedAt(po.getCreatedAt());
      mapper.insert(po);
      return toPersisted(po);
    }
    mapper.updateById(po);
    // updateById 不写 created_at（NOT_NULL 策略），响应仍要回显它，所以回读一次。
    return toPersisted(mapper.selectById(po.getId()));
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
  public List<Persisted<SecretGroupMessage>> listAfterSeq(long secretGroupId, long recipientUserId, long afterSeq,
      int limit) {
    int safeLimit = Math.min(limit <= 0 ? 50 : limit, 100);
    return mapper.selectList(new LambdaQueryWrapper<SecretGroupMessagePo>()
        .eq(SecretGroupMessagePo::getSecretGroupId, secretGroupId)
        .eq(SecretGroupMessagePo::getRecipientUserId, recipientUserId)
        .eq(SecretGroupMessagePo::getStatus, "active")
        .gt(SecretGroupMessagePo::getSeq, afterSeq)
        .orderByAsc(SecretGroupMessagePo::getSeq)
        .last("LIMIT " + safeLimit)).stream().map(this::toPersisted).toList();
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
  public Optional<Persisted<SecretGroupMessage>> findByMsgId(long secretGroupId, String msgId) {
    return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<SecretGroupMessagePo>()
        .eq(SecretGroupMessagePo::getSecretGroupId, secretGroupId)
        .eq(SecretGroupMessagePo::getMsgId, msgId)
        .last("LIMIT 1"))).map(this::toPersisted);
  }

  @Override
  public List<Persisted<SecretGroupMessage>> findUncountedReadBy(long secretGroupId, long recipientUserId,
      long afterSeq, long viewerId) {
    return mapper.selectList(new LambdaQueryWrapper<SecretGroupMessagePo>()
        .eq(SecretGroupMessagePo::getSecretGroupId, secretGroupId)
        .eq(SecretGroupMessagePo::getRecipientUserId, recipientUserId)
        .le(SecretGroupMessagePo::getSeq, afterSeq)
        .ne(SecretGroupMessagePo::getFromUserId, viewerId)
        .eq(SecretGroupMessagePo::getStatus, "active")
        .isNull(SecretGroupMessagePo::getDestroyAt)
        .orderByAsc(SecretGroupMessagePo::getSeq)).stream().map(this::toPersisted).toList();
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
  public List<Persisted<SecretGroupMessage>> findExpired(LocalDateTime now, int limit) {
    int safeLimit = Math.min(limit <= 0 ? 100 : limit, 500);
    return mapper.selectList(new LambdaQueryWrapper<SecretGroupMessagePo>()
        .eq(SecretGroupMessagePo::getStatus, "active")
        .isNotNull(SecretGroupMessagePo::getDestroyAt)
        .le(SecretGroupMessagePo::getDestroyAt, now)
        .orderByAsc(SecretGroupMessagePo::getDestroyAt)
        .last("LIMIT " + safeLimit)).stream().map(this::toPersisted).toList();
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
    return po;
  }

  private SecretGroupMessage toDomain(SecretGroupMessagePo po) {
    return SecretGroupMessage.restore(po.getId(), po.getSecretGroupId(), po.getMsgId(), po.getFromUserId(),
        po.getRecipientUserId(), po.getCiphertext(), po.getSeq(), po.getStatus(), po.getDestroyAt());
  }

  /** 业务聚合 + 记账快照：响应要回显投递时刻，而它不进领域模型。 */
  private Persisted<SecretGroupMessage> toPersisted(SecretGroupMessagePo po) {
    if (po == null) {
      return null;
    }
    return new Persisted<>(toDomain(po),
        new AuditSnapshot(null, po.getCreatedAt(), null, po.getUpdatedAt()));
  }
}
