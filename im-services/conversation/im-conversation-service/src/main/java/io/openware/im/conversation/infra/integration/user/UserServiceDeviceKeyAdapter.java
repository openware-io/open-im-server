package io.openware.im.conversation.infra.integration.user;

import io.openware.im.conversation.domain.secretchat.port.DeviceKeyPort;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.im.user.api.authorization.UserDeviceKeyQuery;
import io.openware.im.user.api.authorization.UserDeviceKeySummary;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 通过用户服务内部接口批量查询设备公钥。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UserServiceDeviceKeyAdapter implements DeviceKeyPort {
  private final UserServiceProperties properties;
  private final InternalServiceAuthenticationInterceptor authenticationInterceptor;

  @Override
  public Map<Long, String> findLatestByUserIds(List<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return Map.of();
    }
    try {
      UserDeviceKeySummary[] summaries = RestClient.builder().baseUrl(properties.getBaseUrl())
          .requestInterceptor(authenticationInterceptor).build().post()
          .uri("/internal/user/authorizations/device-keys")
          .body(new UserDeviceKeyQuery(userIds)).retrieve().body(UserDeviceKeySummary[].class);
      if (summaries == null) {
        return Map.of();
      }
      return Arrays.stream(summaries).collect(Collectors.toMap(
          UserDeviceKeySummary::userId, UserDeviceKeySummary::publicKey, (left, right) -> left));
    } catch (RestClientException exception) {
      log.warn("Unable to load device public keys from user service", exception);
      return Map.of();
    }
  }
}
