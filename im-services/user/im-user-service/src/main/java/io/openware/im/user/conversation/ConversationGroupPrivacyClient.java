package io.openware.im.user.conversation;

import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 调用会话服务内部接口，校验群是否允许成员互加好友（群级隐私开关）。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationGroupPrivacyClient {
  private final RestClient.Builder restClientBuilder;
  private final ConversationServiceProperties properties;
  private final InternalServiceAuthenticationInterceptor authenticationInterceptor;

  /** 群是否允许成员互加好友；群不存在或查询失败时按允许处理，不阻塞好友申请主流程。 */
  public boolean memberFriendRequestAllowed(long groupId) {
    try {
      Map<?, ?> result = client().get()
          .uri("/internal/groups/{id}/member-friend-request-allowed", groupId)
          .retrieve().body(Map.class);
      if (result == null) return true;
      return !Boolean.FALSE.equals(result.get("allowed"));
    } catch (RestClientException exception) {
      log.warn("Unable to query group member friend request policy, groupId={}", groupId, exception);
      return true;
    }
  }

  private RestClient client() {
    return restClientBuilder.clone().baseUrl(properties.getBaseUrl())
        .requestInterceptor(authenticationInterceptor).build();
  }
}
