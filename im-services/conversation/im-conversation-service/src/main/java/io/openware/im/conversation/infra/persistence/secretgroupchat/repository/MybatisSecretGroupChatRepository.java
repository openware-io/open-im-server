package io.openware.im.conversation.infra.persistence.secretgroupchat.repository;

import io.openware.im.conversation.domain.secretgroupchat.model.SecretGroupChat;
import io.openware.im.conversation.domain.secretgroupchat.repository.AuditSnapshot;
import io.openware.im.conversation.domain.secretgroupchat.repository.Persisted;
import io.openware.im.conversation.domain.secretgroupchat.repository.SecretGroupChatRepository;
import io.openware.im.conversation.infra.persistence.secretgroupchat.mapper.SecretGroupChatMapper;
import io.openware.im.conversation.infra.persistence.secretgroupchat.po.SecretGroupChatPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

/**
 * 私密群聊仓储实现。
 *
 * <p><b>审计列在本层确定</b>（standards/10 §聚合装配与持久化契约）：领域模型不带
 * {@code createdBy/createdAt/updatedBy/updatedAt}，写库时由本层打时间戳、读库时经
 * {@link AuditSnapshot} 单独带出。
 *
 * <p><b>更新的列行为</b>：{@code updateById} 使用 NOT_NULL 策略，因此本层只给
 * {@code updatedAt} 赋值；{@code created_*} 保持 null 即不参与 SET，库里原值不被改写——
 * 这正是"创建时刻不可变"要的效果，不需要额外一次 SELECT。
 */
@Component
@RequiredArgsConstructor
public class MybatisSecretGroupChatRepository implements SecretGroupChatRepository {
  private final SecretGroupChatMapper mapper;

  @Override
  public Persisted<SecretGroupChat> save(SecretGroupChat group) {
    SecretGroupChatPo po = toPo(group);
    LocalDateTime now = LocalDateTime.now();
    if (po.getId() == null) {
      po.setCreatedAt(now);
      po.setUpdatedAt(now);
      mapper.insert(po);
      return toPersisted(po);
    }
    po.setUpdatedAt(now);
    mapper.updateById(po);
    // updateById 不写 created_*（NOT_NULL 策略），响应仍要回显它们，所以回读一次。
    return toPersisted(mapper.selectById(po.getId()));
  }

  @Override
  public Optional<Persisted<SecretGroupChat>> findById(long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(this::toPersisted);
  }

  @Override
  public Optional<Persisted<SecretGroupChat>> findByInviteToken(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(mapper.selectOne(
        new LambdaQueryWrapper<SecretGroupChatPo>()
            .eq(SecretGroupChatPo::getInviteToken, token))).map(this::toPersisted);
  }

  @Override
  public List<Persisted<SecretGroupChat>> findByIds(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return mapper.selectByIds(ids).stream().map(this::toPersisted).toList();
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
    return po;
  }

  private SecretGroupChat toDomain(SecretGroupChatPo po) {
    return SecretGroupChat.restore(po.getId(), po.getOwnerUserId(), po.getStatus(), po.getName(), po.getAnnouncement(),
        po.getSafeCode(), po.getDestroyPolicy(), Boolean.TRUE.equals(po.getAnonymousEnabled()), po.getPinnedMsgId(),
        po.getPinnedAt(), po.getInviteToken(), po.getInviteExpiresAt(), Boolean.TRUE.equals(po.getOwnerOnlyPost()));
  }

  /** 业务聚合 + 记账快照：响应要回显四个审计列，而它们不进领域模型。 */
  private Persisted<SecretGroupChat> toPersisted(SecretGroupChatPo po) {
    if (po == null) {
      return null;
    }
    return new Persisted<>(toDomain(po),
        new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt()));
  }
}
