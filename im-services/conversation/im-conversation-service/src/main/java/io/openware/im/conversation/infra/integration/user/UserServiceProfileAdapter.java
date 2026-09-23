package io.openware.im.conversation.infra.integration.user;

import io.openware.im.conversation.domain.group.port.UserProfilePort;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.im.user.api.authorization.UserProfileSummariesQuery;
import io.openware.im.user.api.authorization.UserProfileSummary;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Slf4j
@RequiredArgsConstructor
public class UserServiceProfileAdapter implements UserProfilePort {
  private final UserServiceProperties properties;
  private final InternalServiceAuthenticationInterceptor authenticationInterceptor;

  @Override
  public Map<Long, UserProfile> findByUserIds(List<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) return Map.of();
    try {
      UserProfileSummary[] summaries = RestClient.builder().baseUrl(properties.getBaseUrl())
          .requestInterceptor(authenticationInterceptor).build().post()
          .uri("/internal/user/authorizations/profile-summaries")
          .body(new UserProfileSummariesQuery(userIds)).retrieve().body(UserProfileSummary[].class);
      if (summaries == null) return Map.of();
      return java.util.Arrays.stream(summaries).collect(Collectors.toMap(UserProfileSummary::userId,
          summary -> new UserProfile(summary.username(), summary.nickname(), summary.avatar()), (left, right) -> left));
    } catch (RestClientException exception) {
      log.warn("Unable to load group member profile summaries", exception);
      return Map.of();
    }
  }
}
