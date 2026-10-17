package io.openware.im.user.application.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.common.dto.PageResult;
import io.openware.common.enums.ClientPlatform;
import io.openware.common.enums.PushProvider;
import io.openware.im.user.application.device.command.RegisterDeviceTokenCommand;
import io.openware.im.user.application.device.command.RemoveDeviceTokenCommand;
import io.openware.im.user.application.device.result.DeviceTokenResult;
import io.openware.im.user.domain.device.model.DeviceToken;
import io.openware.im.user.domain.device.readmodel.DeviceTokenView;
import io.openware.im.user.domain.device.repository.Persisted;
import io.openware.im.user.domain.device.repository.DeviceTokenRepository;
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
    public Optional<Persisted<DeviceToken>> findByUserIdAndPushProviderAndToken(Long userId,
        PushProvider pushProvider, String token) {
      return tokens.stream().filter(item -> item.getUserId().equals(userId)
          && item.getPushProvider() == pushProvider && item.getToken().equals(token)).findFirst()
          .map(InMemoryDeviceTokenRepository::persisted);
    }

    @Override
    public List<Persisted<DeviceToken>> findEnabledByUserId(Long userId) {
      return tokens.stream().filter(item -> item.getUserId().equals(userId) && item.getEnabled())
          .map(InMemoryDeviceTokenRepository::persisted).toList();
    }

    @Override
    public List<Persisted<DeviceToken>> findEnabledByUserIds(Collection<Long> userIds) {
      return tokens.stream().filter(item -> userIds.contains(item.getUserId()) && item.getEnabled())
          .map(InMemoryDeviceTokenRepository::persisted).toList();
    }

    @Override
    public Optional<Persisted<DeviceToken>> findById(Long id) {
      return tokens.stream().filter(item -> id.equals(item.getId())).findFirst()
          .map(InMemoryDeviceTokenRepository::persisted);
    }

    @Override
    public PageResult<DeviceTokenView> findEnabledForAdmin(Long userId, int page, int pageSize) {
      return PageResult.<DeviceTokenView>builder().items(List.of()).total(0).page(page).pageSize(pageSize).build();
    }

    @Override
    public Persisted<DeviceToken> save(DeviceToken deviceToken) {
      if (deviceToken.getId() == null) {
        deviceToken.assignId(nextId++);
        tokens.add(deviceToken);
      }
      return persisted(deviceToken);
    }

    /** 领域模型不带审计列：时刻由假仓储补齐，与真实适配器的 AuditSnapshot 同职责。 */
    private static Persisted<DeviceToken> persisted(DeviceToken token) {
      java.time.LocalDateTime now = java.time.LocalDateTime.now();
      return new Persisted<>(token,
          new io.openware.im.user.domain.device.repository.AuditSnapshot(null, now, null, now));
    }
  }
}
