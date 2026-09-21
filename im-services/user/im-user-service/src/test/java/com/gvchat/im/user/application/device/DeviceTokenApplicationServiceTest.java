package com.gvchat.im.user.application.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gvchat.common.dto.PageResult;
import com.gvchat.common.enums.ClientPlatform;
import com.gvchat.common.enums.PushProvider;
import com.gvchat.im.user.application.device.command.RegisterDeviceTokenCommand;
import com.gvchat.im.user.application.device.command.RemoveDeviceTokenCommand;
import com.gvchat.im.user.application.device.result.DeviceTokenResult;
import com.gvchat.im.user.domain.device.model.DeviceToken;
import com.gvchat.im.user.domain.device.repository.DeviceTokenRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeviceTokenApplicationServiceTest {
  @Test
  void shouldCreateAndRefreshDeviceTokenWithDefaultProvider() {
    InMemoryDeviceTokenRepository repository = new InMemoryDeviceTokenRepository();
    DeviceTokenApplicationService service = new DeviceTokenApplicationService(repository);

    DeviceTokenResult created = service.register(7L,
        new RegisterDeviceTokenCommand("token", ClientPlatform.ANDROID, null, "device-1"));
    DeviceTokenResult refreshed = service.register(7L,
        new RegisterDeviceTokenCommand("token", ClientPlatform.IOS, null, "device-2"));

    assertEquals(PushProvider.JPUSH, created.pushProvider());
    assertEquals(created.id(), refreshed.id());
    assertEquals(ClientPlatform.IOS, refreshed.platform());
    assertEquals("device-2", refreshed.deviceId());
    assertTrue(refreshed.enabled());
    assertEquals(1, repository.tokens.size());
  }

  @Test
  void shouldDisableExistingTokenAndIgnoreMissingToken() {
    InMemoryDeviceTokenRepository repository = new InMemoryDeviceTokenRepository();
    DeviceTokenApplicationService service = new DeviceTokenApplicationService(repository);
    service.register(7L, new RegisterDeviceTokenCommand("token", ClientPlatform.ANDROID,
        PushProvider.FCM, "device-1"));

    service.remove(7L, new RemoveDeviceTokenCommand("token", PushProvider.FCM));
    service.remove(7L, new RemoveDeviceTokenCommand("missing", PushProvider.FCM));

    assertFalse(repository.tokens.getFirst().getEnabled());
    assertTrue(service.getTokensForUser(7L).isEmpty());
  }

  private static final class InMemoryDeviceTokenRepository implements DeviceTokenRepository {
    private final List<DeviceToken> tokens = new ArrayList<>();
    private long nextId = 1;

    @Override
    public Optional<DeviceToken> findByUserIdAndPushProviderAndToken(Long userId, PushProvider pushProvider,
        String token) {
      return tokens.stream().filter(item -> item.getUserId().equals(userId)
          && item.getPushProvider() == pushProvider && item.getToken().equals(token)).findFirst();
    }

    @Override
    public List<DeviceToken> findEnabledByUserId(Long userId) {
      return tokens.stream().filter(item -> item.getUserId().equals(userId) && item.getEnabled()).toList();
    }

    @Override
    public List<DeviceToken> findEnabledByUserIds(Collection<Long> userIds) {
      return tokens.stream().filter(item -> userIds.contains(item.getUserId()) && item.getEnabled()).toList();
    }

    @Override
    public Optional<DeviceToken> findById(Long id) {
      return tokens.stream().filter(item -> id.equals(item.getId())).findFirst();
    }

    @Override
    public PageResult<DeviceToken> findEnabledForAdmin(Long userId, int page, int pageSize) {
      return PageResult.<DeviceToken>builder().items(List.of()).total(0).page(page).pageSize(pageSize).build();
    }

    @Override
    public DeviceToken save(DeviceToken deviceToken) {
      if (deviceToken.getId() == null) {
        deviceToken.assignId(nextId++);
        tokens.add(deviceToken);
      }
      return deviceToken;
    }
  }
}
