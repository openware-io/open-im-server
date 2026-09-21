package com.gvchat.im.user.infra.persistence.device.converter;

import com.gvchat.im.user.domain.device.model.DeviceToken;
import com.gvchat.im.user.infra.persistence.device.po.DeviceTokenPo;

public final class DeviceTokenPersistenceConverter {
  private DeviceTokenPersistenceConverter() {
  }

  public static DeviceToken toDomain(DeviceTokenPo po) {
    DeviceToken deviceToken = new DeviceToken();
    deviceToken.restore(po.getId(), po.getUserId(), po.getToken(), po.getPushProvider(), po.getPlatform(),
        po.getDeviceId(), po.getEnabled(), po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt());
    return deviceToken;
  }

  public static DeviceTokenPo toPo(DeviceToken deviceToken) {
    DeviceTokenPo po = new DeviceTokenPo();
    po.setId(deviceToken.getId());
    po.setUserId(deviceToken.getUserId());
    po.setToken(deviceToken.getToken());
    po.setPushProvider(deviceToken.getPushProvider());
    po.setPlatform(deviceToken.getPlatform());
    po.setDeviceId(deviceToken.getDeviceId());
    po.setEnabled(deviceToken.getEnabled());
    po.setCreatedBy(deviceToken.getCreatedBy());
    po.setCreatedAt(deviceToken.getCreatedAt());
    po.setUpdatedBy(deviceToken.getUpdatedBy());
    po.setUpdatedAt(deviceToken.getUpdatedAt());
    return po;
  }
}
