package io.openware.im.conversation.infra.integration.user;

import io.openware.im.conversation.domain.group.port.ActiveUserPort;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.im.user.api.authorization.ActiveUsersQuery;
import io.openware.im.user.api.authorization.ActiveUsersSnapshot;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class UserServiceActiveUserAdapter implements ActiveUserPort {
  private final UserServiceProperties properties;
  private final InternalServiceAuthenticationInterceptor authenticationInterceptor;

  @Override
  public List<Long> findActiveUserIds(List<Long> userIds) {
    ActiveUsersSnapshot snapshot = RestClient.builder().baseUrl(properties.getBaseUrl())
        .requestInterceptor(authenticationInterceptor).build().post()
        .uri("/internal/user/authorizations/active-users").body(new ActiveUsersQuery(userIds)).retrieve()
        .body(ActiveUsersSnapshot.class);
    return snapshot == null || snapshot.activeUserIds() == null ? List.of() : snapshot.activeUserIds();
  }
}
