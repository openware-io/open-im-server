package io.openware.im.user.infra.persistence.device.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.im.user.domain.device.model.DeviceSession;
import io.openware.im.user.domain.device.repository.DeviceSessionRepository;
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
  public Optional<DeviceSession> findByUserIdAndDeviceId(Long userId, String deviceId) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<DeviceSessionPo>lambdaQuery()
        .eq(DeviceSessionPo::getUserId, userId)
        .eq(DeviceSessionPo::getDeviceId, deviceId))).map(DeviceSessionPersistenceConverter::toDomain);
  }

  @Override
  public Optional<DeviceSession> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(DeviceSessionPersistenceConverter::toDomain);
  }

  @Override
  public List<DeviceSession> findByUserId(Long userId) {
    return mapper.selectList(Wrappers.<DeviceSessionPo>lambdaQuery()
            .eq(DeviceSessionPo::getUserId, userId)
            .orderByDesc(DeviceSessionPo::getLastActiveAt))
        .stream().map(DeviceSessionPersistenceConverter::toDomain).toList();
  }

  @Override
  public DeviceSession save(DeviceSession session) {
    DeviceSessionPo po = DeviceSessionPersistenceConverter.toPo(session);
    if (po.getId() == null) {
      mapper.insert(po);
      session.assignId(po.getId());
    } else {
      mapper.updateById(po);
    }
    return session;
  }
}
