package io.openware.im.message.infra.persistence.secretgroupmessage.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.im.message.domain.secretgroupmessage.model.SecretGroupMessageDestroyed;
import io.openware.im.message.domain.secretgroupmessage.repository.SecretGroupMessageDestroyedRepository;
import io.openware.im.message.infra.persistence.secretgroupmessage.mapper.SecretGroupMessageDestroyedMapper;
import io.openware.im.message.infra.persistence.secretgroupmessage.po.SecretGroupMessageDestroyedPo;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisSecretGroupMessageDestroyedRepository implements SecretGroupMessageDestroyedRepository {
  private final SecretGroupMessageDestroyedMapper mapper;

  @Override
  public void save(SecretGroupMessageDestroyed destroyed) {
    SecretGroupMessageDestroyedPo po = new SecretGroupMessageDestroyedPo();
    po.setSecretGroupId(destroyed.secretGroupId());
    po.setMsgId(destroyed.msgId());
    po.setDestroyAt(destroyed.destroyAt());
    po.setReason(destroyed.reason());
    mapper.insert(po);
  }

  @Override
  public List<SecretGroupMessageDestroyed> listAfter(long secretGroupId, LocalDateTime afterDestroyAt, int limit) {
    int safeLimit = Math.min(limit <= 0 ? 100 : limit, 500);
    return mapper.selectList(new LambdaQueryWrapper<SecretGroupMessageDestroyedPo>()
        .eq(SecretGroupMessageDestroyedPo::getSecretGroupId, secretGroupId)
        .gt(SecretGroupMessageDestroyedPo::getDestroyAt, afterDestroyAt)
        .orderByAsc(SecretGroupMessageDestroyedPo::getDestroyAt)
        .last("LIMIT " + safeLimit)).stream()
        .map(po -> new SecretGroupMessageDestroyed(po.getSecretGroupId(), po.getMsgId(), po.getDestroyAt(), po.getReason()))
        .toList();
  }
}
