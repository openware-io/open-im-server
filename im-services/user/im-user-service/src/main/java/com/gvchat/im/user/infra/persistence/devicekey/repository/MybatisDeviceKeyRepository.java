package com.gvchat.im.user.infra.persistence.devicekey.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.im.user.domain.devicekey.model.DeviceKey;
import com.gvchat.im.user.domain.devicekey.repository.DeviceKeyRepository;
import com.gvchat.im.user.infra.persistence.devicekey.mapper.DeviceKeyMapper;
import com.gvchat.im.user.infra.persistence.devicekey.po.DeviceKeyPo;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisDeviceKeyRepository implements DeviceKeyRepository {
  private final DeviceKeyMapper deviceKeyMapper;

  @Override
  public Optional<DeviceKey> findByUserIdAndDeviceId(long userId, String deviceId) {
    DeviceKeyPo po = deviceKeyMapper.selectOne(new LambdaQueryWrapper<DeviceKeyPo>()
        .eq(DeviceKeyPo::getUserId, userId)
        .eq(DeviceKeyPo::getDeviceId, deviceId));
    return Optional.ofNullable(po).map(this::toDomain);
  }

  @Override
  public List<DeviceKey> findEnabledByUserId(long userId) {
    return deviceKeyMapper.selectList(new LambdaQueryWrapper<DeviceKeyPo>()
        .eq(DeviceKeyPo::getUserId, userId)
        .eq(DeviceKeyPo::getStatus, "active")).stream().map(this::toDomain).toList();
  }

  @Override
  public DeviceKey save(DeviceKey deviceKey) {
    DeviceKeyPo po = toPo(deviceKey);
    if (po.getId() == null) {
      deviceKeyMapper.insert(po);
    } else {
      deviceKeyMapper.updateById(po);
    }
    return toDomain(po);
  }

  private DeviceKeyPo toPo(DeviceKey key) {
    DeviceKeyPo po = new DeviceKeyPo();
    po.setId(key.getId());
    po.setUserId(key.getUserId());
    po.setDeviceId(key.getDeviceId());
    po.setPublicKey(key.getPublicKey());
    po.setStatus(key.getStatus());
    po.setCreatedBy(key.getCreatedBy());
    po.setCreatedAt(key.getCreatedAt());
    po.setUpdatedBy(key.getUpdatedBy());
    po.setUpdatedAt(key.getUpdatedAt());
    return po;
  }

  private DeviceKey toDomain(DeviceKeyPo po) {
    return DeviceKey.restore(po.getId(), po.getUserId(), po.getDeviceId(), po.getPublicKey(), po.getStatus(),
        po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt());
  }
}
