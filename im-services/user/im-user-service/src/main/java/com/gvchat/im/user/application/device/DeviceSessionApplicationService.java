package com.gvchat.im.user.application.device;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.im.user.application.device.command.RecordDeviceSessionCommand;
import com.gvchat.im.user.application.device.result.DeviceSessionResult;
import com.gvchat.im.user.domain.device.model.DeviceSession;
import com.gvchat.im.user.domain.device.model.LoginMethod;
import com.gvchat.im.user.domain.device.repository.DeviceSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备登录会话应用服务：记录多端登录会话、查询设备列表，以及踢出/退出设备。
 *
 * <p>同一 (user_id, device_id) 复用同一条会话（刷新最后活跃时间与登录信息），
 * 从而支持「一号多设备」在线与登录 IP/方式统计（参考百度网盘登录账号管理）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceSessionApplicationService {
  private final DeviceSessionRepository deviceSessionRepository;

  /** 记录（或刷新）一次登录会话：同设备复用会话并刷新最后活跃时间。 */
  @Transactional
  public void recordLogin(RecordDeviceSessionCommand command) {
    String deviceId = normalizeDeviceId(command.deviceId(), command.loginMethod());
    LocalDateTime now = LocalDateTime.now();
    DeviceSession session = deviceSessionRepository.findByUserIdAndDeviceId(command.userId(), deviceId)
        .map(existing -> {
          existing.refreshActivity(command.deviceType(), command.deviceName(), command.loginMethod(), command.ip(), now);
          return existing;
        })
        .orElseGet(() -> DeviceSession.start(command.userId(), deviceId, command.deviceType(), command.deviceName(),
            command.loginMethod(), command.ip(), now));
    deviceSessionRepository.save(session);
  }

  /** 查询当前账号全部设备会话，按最后活跃时间倒序。 */
  @Transactional(readOnly = true)
  public List<DeviceSessionResult> listDevices(Long userId) {
    return deviceSessionRepository.findByUserId(userId).stream().map(this::toResult).toList();
  }

  /** 主设备踢出指定设备（按 deviceId）。 */
  @Transactional
  public void kick(Long userId, String deviceId) {
    DeviceSession session = requireOwnedSession(userId, deviceId);
    session.kick(LocalDateTime.now());
    deviceSessionRepository.save(session);
    log.info("[device-session] kicked device, userId={}, deviceId={}", userId, deviceId);
  }

  /** 副设备主动退出（只影响自身）。 */
  @Transactional
  public void logout(Long userId, String deviceId) {
    DeviceSession session = requireOwnedSession(userId, deviceId);
    session.logout(LocalDateTime.now());
    deviceSessionRepository.save(session);
    log.info("[device-session] logout device, userId={}, deviceId={}", userId, deviceId);
  }

  private DeviceSession requireOwnedSession(Long userId, String deviceId) {
    return deviceSessionRepository.findByUserIdAndDeviceId(userId, deviceId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Device session not found"));
  }

  private DeviceSessionResult toResult(DeviceSession session) {
    return new DeviceSessionResult(session.getId(), session.getUserId(), session.getDeviceId(), session.getDeviceType(),
        session.getDeviceName(), session.getLoginIp(), session.getLoginMethod(), session.getLastActiveAt(),
        session.getLastActiveIp(), session.getStatus(), session.getCreatedAt(), session.getUpdatedAt());
  }

  /** 设备标识兜底：未提供时按登录方式生成稳定的占位标识，保证 (user_id, device_id) 唯一约束可复用。 */
  private static String normalizeDeviceId(String deviceId, LoginMethod method) {
    if (deviceId != null && !deviceId.isBlank()) {
      return deviceId.trim();
    }
    LoginMethod fallback = method == null ? LoginMethod.PASSWORD : method;
    return "default-" + fallback.value();
  }
}
