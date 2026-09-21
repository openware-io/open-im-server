package com.gvchat.im.user.infra.persistence.device.converter;

import com.gvchat.im.user.domain.device.model.DeviceSession;
import com.gvchat.im.user.domain.device.model.DeviceSessionStatus;
import com.gvchat.im.user.domain.device.model.LoginMethod;
import com.gvchat.im.user.infra.persistence.device.po.DeviceSessionPo;

public final class DeviceSessionPersistenceConverter {
  private DeviceSessionPersistenceConverter() {
  }

  public static DeviceSession toDomain(DeviceSessionPo po) {
    DeviceSession session = new DeviceSession();
    session.restore(po.getId(), po.getUserId(), po.getDeviceId(), po.getDeviceType(), po.getDeviceName(),
        po.getLoginIp(), LoginMethod.fromValue(po.getLoginMethod()), po.getLastActiveAt(), po.getLastActiveIp(),
        DeviceSessionStatus.fromValue(po.getStatus()), po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(),
        po.getUpdatedAt());
    return session;
  }

  public static DeviceSessionPo toPo(DeviceSession session) {
    DeviceSessionPo po = new DeviceSessionPo();
    po.setId(session.getId());
    po.setUserId(session.getUserId());
    po.setDeviceId(session.getDeviceId());
    po.setDeviceType(session.getDeviceType());
    po.setDeviceName(session.getDeviceName());
    po.setLoginIp(session.getLoginIp());
    po.setLoginMethod(session.getLoginMethod().value());
    po.setLastActiveAt(session.getLastActiveAt());
    po.setLastActiveIp(session.getLastActiveIp());
    po.setStatus(session.getStatus().value());
    po.setCreatedBy(session.getCreatedBy());
    po.setCreatedAt(session.getCreatedAt());
    po.setUpdatedBy(session.getUpdatedBy());
    po.setUpdatedAt(session.getUpdatedAt());
    return po;
  }
}
