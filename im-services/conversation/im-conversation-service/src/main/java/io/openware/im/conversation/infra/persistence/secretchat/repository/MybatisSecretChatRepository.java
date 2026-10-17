package io.openware.im.conversation.infra.persistence.secretchat.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.im.conversation.domain.secretchat.model.SecretChat;
import io.openware.im.conversation.domain.secretchat.repository.AuditSnapshot;
import io.openware.im.conversation.domain.secretchat.repository.Persisted;
import io.openware.im.conversation.domain.secretchat.repository.SecretChatRepository;
import io.openware.im.conversation.infra.persistence.secretchat.mapper.SecretChatMapper;
import io.openware.im.conversation.infra.persistence.secretchat.po.SecretChatPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 私密聊天仓储实现。
 *
 * <p><b>审计列在本层确定</b>（standards/10 §聚合装配与持久化契约）：领域模型不带
 * {@code createdBy/createdAt/updatedBy/updatedAt}，写库时由本层打时间戳、读库时经
 * {@link AuditSnapshot} 单独带出。
 *
 * <p><b>更新的列行为</b>：{@code updateById} 使用 NOT_NULL 策略，因此本层只给
 * {@code updatedBy/updatedAt} 赋值；{@code createdBy/createdAt} 保持 null 即不参与 SET，库里原值不被改写——
 * 这正是"创建时刻不可变"要的效果，且不需要额外一次 SELECT。
 */
@Component
@RequiredArgsConstructor
public class MybatisSecretChatRepository implements SecretChatRepository {
  private final SecretChatMapper secretChatMapper;

  @Override
  public Persisted<SecretChat> save(SecretChat secretChat) {
    SecretChatPo po = toPo(secretChat);
    LocalDateTime now = LocalDateTime.now();
    if (po.getId() == null) {
      // 新建：两列都写；createdBy/updatedBy 保持既有口径（DDL 默认 0 = 系统）。
      po.setCreatedAt(now);
      po.setUpdatedAt(now);
      secretChatMapper.insert(po);
    } else {
      po.setUpdatedAt(now);
      secretChatMapper.updateById(po);
      // updateById 不写 created_*（NOT_NULL 策略下 null 不参与 SET），响应仍要回显它们，
      // 所以回读一次；这是更新路径上唯一多出的一次查询，换来领域模型不带审计列。
      return toPersisted(secretChatMapper.selectById(po.getId()));
    }
    return toPersisted(po);
  }

  @Override
  public Optional<Persisted<SecretChat>> findById(long id) {
    return Optional.ofNullable(secretChatMapper.selectById(id)).map(this::toPersisted);
  }

  @Override
  public Optional<Persisted<SecretChat>> findBetween(long userA, long userB) {
    SecretChatPo po = secretChatMapper.selectOne(new LambdaQueryWrapper<SecretChatPo>()
        .eq(SecretChatPo::getUserA, userA).eq(SecretChatPo::getUserB, userB));
    return Optional.ofNullable(po).map(this::toPersisted);
  }

  @Override
  public long countBetween(long userA, long userB) {
    return secretChatMapper.selectCount(new LambdaQueryWrapper<SecretChatPo>()
        .eq(SecretChatPo::getUserA, userA).eq(SecretChatPo::getUserB, userB));
  }

  @Override
  public List<Persisted<SecretChat>> findByParticipant(long userId) {
    return secretChatMapper.selectList(new LambdaQueryWrapper<SecretChatPo>()
        .and(w -> w.eq(SecretChatPo::getUserA, userId).or().eq(SecretChatPo::getUserB, userId)))
        .stream().map(this::toPersisted).toList();
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
    return po;
  }

  private SecretChat toDomain(SecretChatPo po) {
    return SecretChat.restore(po.getId(), po.getUserA(), po.getUserB(), po.getStatus(), po.getSafeCode(),
        po.getDestroyPolicy(), po.getUserAPublicKey(), po.getUserBPublicKey(), po.getHandshakeState());
  }

  /** 业务聚合 + 记账快照：响应要回显四个审计列，而它们不进领域模型。 */
  private Persisted<SecretChat> toPersisted(SecretChatPo po) {
    if (po == null) {
      return null;
    }
    return new Persisted<>(toDomain(po),
        new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt()));
  }
}
