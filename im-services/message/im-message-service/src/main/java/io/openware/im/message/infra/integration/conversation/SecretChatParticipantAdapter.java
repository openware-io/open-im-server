package io.openware.im.message.infra.integration.conversation;

import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.im.message.domain.secretmessage.port.SecretChatParticipantPort;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 私密会话参与方适配器：调用会话服务内部接口读取 userA/userB；失败返回 0（不推送）。 */
@Component
@Slf4j
public class SecretChatParticipantAdapter implements SecretChatParticipantPort {
  private final RestClient restClient;

  public SecretChatParticipantAdapter(RestClient.Builder builder, ConversationServiceProperties properties,
      InternalServiceAuthenticationInterceptor interceptor) {
    this.restClient = builder.baseUrl(properties.getBaseUrl()).requestInterceptor(interceptor).build();
  }

  @Override
  public long findPeerUserId(long secretChatId, long senderId) {
    Map<String, Long> response = fetchParticipants(secretChatId);
    if (response.isEmpty()) {
      return 0L;
    }
    Long userA = response.get("userA");
    Long userB = response.get("userB");
    if (userA == null || userB == null) {
      return 0L;
    }
    return Long.valueOf(senderId).equals(userA) ? userB : userA;
  }

  @Override
  public List<Long> findParticipants(long secretChatId) {
    Map<String, Long> response = fetchParticipants(secretChatId);
    Long userA = response.get("userA");
    Long userB = response.get("userB");
    if (userA == null || userB == null) {
      return List.of();
    }
    return userA.equals(userB) ? List.of(userA) : List.of(userA, userB);
  }

  private Map<String, Long> fetchParticipants(long secretChatId) {
    try {
      Map<String, Long> response = restClient.get()
          .uri("/internal/secret-chats/{secretChatId}/participants", secretChatId)
          .retrieve().body(new ParameterizedTypeReference<Map<String, Long>>() {});
      return response == null ? Map.of() : response;
    } catch (RuntimeException e) {
      log.warn("Failed to fetch secret chat participants, secretChatId={}", secretChatId);
      return Map.of();
    }
  }
}
