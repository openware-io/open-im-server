package io.openware.im.user.domain.device.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class DeviceSessionTest {
  @Test
  void shouldStartAsActiveAndRefreshOnSameDevice() {
    LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0);
    DeviceSession session = DeviceSession.start(7L, "device-1", "mobile", "iPhone", LoginMethod.PASSWORD, "1.1.1.1", now);

    assertTrue(session.isActive());
    assertEquals(LoginMethod.PASSWORD, session.getLoginMethod());
    assertEquals("1.1.1.1", session.getLoginIp());

    session.refreshActivity("mobile", "iPhone 15", LoginMethod.QR_CODE, "2.2.2.2", now.plusMinutes(5));

    assertEquals("iPhone 15", session.getDeviceName());
    assertEquals(LoginMethod.QR_CODE, session.getLoginMethod());
    assertEquals("2.2.2.2", session.getLastActiveIp());
    assertEquals(now.plusMinutes(5), session.getLastActiveAt());
  }

  @Test
  void shouldTransitionToKickedAndLogout() {
    DeviceSession session = DeviceSession.start(7L, "device-1", "mobile", "iPhone", LoginMethod.PASSWORD, "1.1.1.1",
        LocalDateTime.now());

    session.kick(LocalDateTime.now());

    assertEquals(DeviceSessionStatus.KICKED, session.getStatus());
    assertFalse(session.isActive());

    session.logout(LocalDateTime.now());

    assertEquals(DeviceSessionStatus.LOGOUT, session.getStatus());
  }

  @Test
  void shouldNormalizeUnknownLoginMethodToPassword() {
    assertEquals(LoginMethod.PASSWORD, LoginMethod.fromValue(null));
    assertEquals(LoginMethod.QR_CODE, LoginMethod.fromValue("qr_code"));
    assertEquals(LoginMethod.PASSWORD, LoginMethod.fromValue("unknown"));
  }
}
