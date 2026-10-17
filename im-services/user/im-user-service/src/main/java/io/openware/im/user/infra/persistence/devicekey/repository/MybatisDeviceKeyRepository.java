package io.openware.im.user.infra.persistence.devicekey.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.im.user.domain.devicekey.model.DeviceKey;
import io.openware.im.user.domain.devicekey.repository.AuditSnapshot;
import io.openware.im.user.domain.devicekey.repository.DeviceKeyRepository;
import io.openware.im.user.domain.devicekey.repository.Persisted;
import io.openware.im.user.infra.persistence.devicekey.mapper.DeviceKeyMapper;
import io.openware.im.user.infra.persistence.devicekey.po.DeviceKeyPo;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisDeviceKeyRepository implements DeviceKeyRepository {
  private final DeviceKeyMapper deviceKeyMapper;

  @Override
  public Optional<Persisted<DeviceKey>> findByUserIdAndDeviceId(long userId, String deviceId) {
    DeviceKeyPo po = deviceKeyMapper.selectOne(new LambdaQueryWrapper<DeviceKeyPo>()
        .eq(DeviceKeyPo::getUserId, userId)
        .eq(DeviceKeyPo::getDeviceId, deviceId));
    return Optional.ofNullable(po).map(this::toPersisted);
  }

  @Override
  public List<Persisted<DeviceKey>> findEnabledByUserId(long userId) {
    return deviceKeyMapper.selectList(new LambdaQueryWrapper<DeviceKeyPo>()
        .eq(DeviceKeyPo::getUserId, userId)
        .eq(DeviceKeyPo::getStatus, "active")).stream().map(this::toPersisted).toList();
  }

  @Override
  public Persisted<DeviceKey> save(DeviceKey deviceKey) {
    DeviceKeyPo po = toPo(deviceKey);
    // 审计列由适配器确定（standards/10 L299）：操作人恒为密钥所属用户。
    java.time.LocalDateTime now = java.time.LocalDateTime.now();
    po.setCreatedBy(po.getUserId());
    po.setUpdatedBy(po.getUserId());
    if (po.getId() == null) {
      po.setCreatedAt(now);
      po.setUpdatedAt(now);
      deviceKeyMapper.insert(po);
      return toPersisted(po);
    }
    po.setUpdatedAt(now);
    deviceKeyMapper.updateById(po);
    return toPersisted(deviceKeyMapper.selectById(po.getId()));
  }

  private DeviceKeyPo toPo(DeviceKey key) {
    DeviceKeyPo po = new DeviceKeyPo();
    po.setId(key.getId());
    po.setUserId(key.getUserId());
    po.setDeviceId(key.getDeviceId());
    po.setPublicKey(key.getPublicKey());
    po.setStatus(key.getStatus());
    return po;
  }

  private DeviceKey toDomain(DeviceKeyPo po) {
    return DeviceKey.restore(po.getId(), po.getUserId(), po.getDeviceId(), po.getPublicKey(), po.getStatus());
  }

  /** 业务聚合 + 记账快照：响应要回显四个审计列，而它们不进领域模型。 */
  private Persisted<DeviceKey> toPersisted(DeviceKeyPo po) {
    if (po == null) {
      return null;
    }
    return new Persisted<>(toDomain(po),
        new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt()));
  }
}
