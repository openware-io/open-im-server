package io.openware.im.message.infra.integration.user;

import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.im.message.domain.message.port.FriendRelationPort;
import io.openware.im.user.api.authorization.PrivateMessageAuthorizationQuery;
import io.openware.im.user.api.authorization.PrivateMessageAuthorizationSnapshot;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class UserServiceFriendRelationAdapter implements FriendRelationPort {
  private final RestClient restClient;

  public UserServiceFriendRelationAdapter(RestClient.Builder builder, UserServiceProperties properties,
      InternalServiceAuthenticationInterceptor interceptor) {
    this.restClient = builder.baseUrl(properties.getBaseUrl()).requestInterceptor(interceptor).build();
  }

  @Override
  public boolean areFriends(long userId, long peerUserId) {
    PrivateMessageAuthorizationSnapshot response = restClient.post()
        .uri("/internal/user/authorizations/private-message")
        .body(new PrivateMessageAuthorizationQuery(userId, peerUserId,
            "friendship-query-" + userId + "-" + peerUserId, Instant.now()))
        .retrieve().body(PrivateMessageAuthorizationSnapshot.class);
    return response != null && response.allowed();
  }
}
