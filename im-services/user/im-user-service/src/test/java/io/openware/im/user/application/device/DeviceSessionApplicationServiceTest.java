package io.openware.im.user.application.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.im.user.application.device.command.RecordDeviceSessionCommand;
import io.openware.im.user.application.device.result.DeviceSessionResult;
import io.openware.im.user.domain.device.model.DeviceSession;
import io.openware.im.user.domain.device.model.DeviceSessionStatus;
import io.openware.im.user.domain.device.model.LoginMethod;
import io.openware.im.user.domain.device.repository.DeviceSessionRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeviceSessionApplicationServiceTest {
  @Test
  void shouldReuseSessionForSameDeviceAndRefreshLastActive() {
    InMemoryDeviceSessionRepository repository = new InMemoryDeviceSessionRepository();
    DeviceSessionApplicationService service = new DeviceSessionApplicationService(repository);

    service.recordLogin(new RecordDeviceSessionCommand(7L, "device-1", "mobile", "iPhone", LoginMethod.PASSWORD, "1.1.1.1"));
    service.recordLogin(new RecordDeviceSessionCommand(7L, "device-1", "mobile", "iPhone 15", LoginMethod.PASSWORD, "2.2.2.2"));

    assertEquals(1, repository.sessions.size());
    DeviceSession session = repository.sessions.getFirst();
    assertEquals("iPhone 15", session.getDeviceName());
    assertEquals("2.2.2.2", session.getLoginIp());
    assertTrue(session.isActive());
  }

  @Test
  void shouldListAllDevicesWithLoginMethod() {
    InMemoryDeviceSessionRepository repository = new InMemoryDeviceSessionRepository();
    DeviceSessionApplicationService service = new DeviceSessionApplicationService(repository);
    service.recordLogin(new RecordDeviceSessionCommand(7L, "device-1", "mobile", "iPhone", LoginMethod.PASSWORD, "1.1.1.1"));
    service.recordLogin(new RecordDeviceSessionCommand(7L, "device-2", "desktop", "MacBook", LoginMethod.QR_CODE, "2.2.2.2"));

    List<DeviceSessionResult> devices = service.listDevices(7L);

    assertEquals(2, devices.size());
    assertTrue(devices.stream().anyMatch(device -> device.deviceId().equals("device-1")
        && device.loginMethod() == LoginMethod.PASSWORD));
    assertTrue(devices.stream().anyMatch(device -> device.deviceId().equals("device-2")
        && device.loginMethod() == LoginMethod.QR_CODE));
  }

  @Test
  void shouldKickAndLogoutDevice() {
    InMemoryDeviceSessionRepository repository = new InMemoryDeviceSessionRepository();
    DeviceSessionApplicationService service = new DeviceSessionApplicationService(repository);
    service.recordLogin(new RecordDeviceSessionCommand(7L, "device-1", "mobile", "iPhone", LoginMethod.PASSWORD, "1.1.1.1"));

    service.kick(7L, "device-1");

    assertEquals(DeviceSessionStatus.KICKED, repository.sessions.getFirst().getStatus());

    service.logout(7L, "device-1");

    assertEquals(DeviceSessionStatus.LOGOUT, repository.sessions.getFirst().getStatus());
    assertFalse(repository.sessions.getFirst().isActive());
  }

  private static final class InMemoryDeviceSessionRepository implements DeviceSessionRepository {
    private final List<DeviceSession> sessions = new ArrayList<>();
    private long nextId = 1;

    @Override
    public Optional<DeviceSession> findByUserIdAndDeviceId(Long userId, String deviceId) {
      return sessions.stream().filter(item -> item.getUserId().equals(userId) && item.getDeviceId().equals(deviceId))
          .findFirst();
    }

    @Override
    public Optional<DeviceSession> findById(Long id) {
      return sessions.stream().filter(item -> id.equals(item.getId())).findFirst();
    }

    @Override
    public List<DeviceSession> findByUserId(Long userId) {
      return sessions.stream().filter(item -> item.getUserId().equals(userId))
          .sorted(Comparator.comparing(DeviceSession::getLastActiveAt).reversed()).toList();
    }

    @Override
    public DeviceSession save(DeviceSession session) {
      if (session.getId() == null) {
        session.assignId(nextId++);
        sessions.add(session);
      }
      return session;
    }
  }
}
