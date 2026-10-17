package io.openware.im.user.infra.persistence.device.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.im.user.domain.device.model.DeviceSession;
import io.openware.im.user.domain.device.repository.AuditSnapshot;
import io.openware.im.user.domain.device.repository.DeviceSessionRepository;
import io.openware.im.user.domain.device.repository.Persisted;
import io.openware.im.user.infra.persistence.device.converter.DeviceSessionPersistenceConverter;
import io.openware.im.user.infra.persistence.device.mapper.DeviceSessionMapper;
import io.openware.im.user.infra.persistence.device.po.DeviceSessionPo;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DeviceSessionRepositoryAdapter implements DeviceSessionRepository {
  private final DeviceSessionMapper mapper;

  @Override
  public Optional<Persisted<DeviceSession>> findByUserIdAndDeviceId(Long userId, String deviceId) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<DeviceSessionPo>lambdaQuery()
        .eq(DeviceSessionPo::getUserId, userId)
        .eq(DeviceSessionPo::getDeviceId, deviceId))).map(this::toPersisted);
  }

  @Override
  public Optional<Persisted<DeviceSession>> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(this::toPersisted);
  }

  @Override
  public List<Persisted<DeviceSession>> findByUserId(Long userId) {
    return mapper.selectList(Wrappers.<DeviceSessionPo>lambdaQuery()
            .eq(DeviceSessionPo::getUserId, userId)
            .orderByDesc(DeviceSessionPo::getLastActiveAt))
        .stream().map(this::toPersisted).toList();
  }

  @Override
  public Persisted<DeviceSession> save(DeviceSession session) {
    DeviceSessionPo po = DeviceSessionPersistenceConverter.toPo(session);
    // 审计列由适配器确定（standards/10 L299）：会话操作人恒为用户本人。
    java.time.LocalDateTime now = java.time.LocalDateTime.now();
    po.setCreatedBy(po.getUserId());
    po.setUpdatedBy(po.getUserId());
    if (po.getId() == null) {
      po.setCreatedAt(now);
      po.setUpdatedAt(now);
      mapper.insert(po);
      session.assignId(po.getId());
      return toPersisted(po);
    }
    po.setUpdatedAt(now);
    mapper.updateById(po);
    return toPersisted(mapper.selectById(po.getId()));
  }

  /** 业务聚合 + 记账快照：响应要回显建/改时刻，而它们不进领域模型。 */
  private Persisted<DeviceSession> toPersisted(DeviceSessionPo po) {
    if (po == null) {
      return null;
    }
    return new Persisted<>(DeviceSessionPersistenceConverter.toDomain(po),
        new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt()));
  }
}
