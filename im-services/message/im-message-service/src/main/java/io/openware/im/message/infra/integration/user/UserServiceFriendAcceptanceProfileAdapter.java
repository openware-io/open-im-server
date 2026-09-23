package io.openware.im.message.infra.integration.user;

import io.openware.im.user.api.authorization.UserProfileSummariesQuery;
import io.openware.im.user.api.authorization.UserProfileSummary;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.im.message.domain.message.port.FriendAcceptanceProfilePort;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Slf4j
public class UserServiceFriendAcceptanceProfileAdapter implements FriendAcceptanceProfilePort {
  private final RestClient restClient;

  public UserServiceFriendAcceptanceProfileAdapter(RestClient.Builder builder, UserServiceProperties properties,
      InternalServiceAuthenticationInterceptor interceptor) {
    this.restClient = builder.baseUrl(properties.getBaseUrl()).requestInterceptor(interceptor).build();
  }

  @Override
  public SenderProfile find(long userId) {
    List<UserProfileSummary> response;
    try {
      response = restClient.post()
          .uri("/internal/user/authorizations/profile-summaries")
          .body(new UserProfileSummariesQuery(List.of(userId)))
          .retrieve().body(new ParameterizedTypeReference<List<UserProfileSummary>>() {});
    } catch (RuntimeException ex) {
      // 资料服务不可用时不再中断好友通过消息，由上层使用回退标识继续落库。
      log.warn("好友通过自动消息：资料查询调用失败, userId={}, error={}", userId, ex.toString());
      return null;
    }
    if (response == null || response.isEmpty() || response.getFirst() == null
        || response.getFirst().username() == null || response.getFirst().username().isBlank()) {
      // 此前这里抛异常，导致整条好友通过自动消息永远不生成（线上表现为
      // 「加好友并通过后双方都收不到任何提示」）。改为记录原因并返回 null。
      log.warn("好友通过自动消息：资料查询为空, userId={}, responseSize={}", userId,
          response == null ? -1 : response.size());
      return null;
    }
    UserProfileSummary profile = response.getFirst();
    return new SenderProfile(profile.userId(), profile.username());
  }
}
