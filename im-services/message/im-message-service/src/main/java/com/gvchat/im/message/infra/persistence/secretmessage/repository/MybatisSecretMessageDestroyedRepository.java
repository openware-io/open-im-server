package com.gvchat.im.message.infra.persistence.secretmessage.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.im.message.domain.secretmessage.model.SecretMessageDestroyed;
import com.gvchat.im.message.domain.secretmessage.repository.SecretMessageDestroyedRepository;
import com.gvchat.im.message.infra.persistence.secretmessage.mapper.SecretMessageDestroyedMapper;
import com.gvchat.im.message.infra.persistence.secretmessage.po.SecretMessageDestroyedPo;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisSecretMessageDestroyedRepository implements SecretMessageDestroyedRepository {
  private final SecretMessageDestroyedMapper mapper;

  @Override
  public void save(SecretMessageDestroyed destroyed) {
    SecretMessageDestroyedPo po = new SecretMessageDestroyedPo();
    po.setSecretChatId(destroyed.secretChatId());
    po.setMsgId(destroyed.msgId());
    po.setDestroyAt(destroyed.destroyAt());
    po.setReason(destroyed.reason());
    mapper.insert(po);
  }

  @Override
  public List<SecretMessageDestroyed> listAfter(long secretChatId, LocalDateTime afterDestroyAt, int limit) {
    int safeLimit = Math.min(limit <= 0 ? 50 : limit, 100);
    return mapper.selectList(new LambdaQueryWrapper<SecretMessageDestroyedPo>()
        .eq(SecretMessageDestroyedPo::getSecretChatId, secretChatId)
        .gt(SecretMessageDestroyedPo::getDestroyAt, afterDestroyAt)
        .orderByAsc(SecretMessageDestroyedPo::getDestroyAt)
        .last("LIMIT " + safeLimit)).stream()
        .map(po -> new SecretMessageDestroyed(po.getSecretChatId(), po.getMsgId(), po.getDestroyAt(), po.getReason()))
        .toList();
  }
}
