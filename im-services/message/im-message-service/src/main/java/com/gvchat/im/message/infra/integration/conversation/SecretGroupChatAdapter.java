package com.gvchat.im.message.infra.integration.conversation;

import com.gvchat.infrastructure.security.InternalServiceAuthenticationInterceptor;
import com.gvchat.im.message.domain.secretgroupmessage.port.SecretGroupChatPort;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 私密群聊适配器：调用会话服务内部接口读取成员与销毁策略；失败按空/off 兜底。 */
@Component
@Slf4j
public class SecretGroupChatAdapter implements SecretGroupChatPort {
  private final RestClient restClient;

  public SecretGroupChatAdapter(RestClient.Builder builder, ConversationServiceProperties properties,
      InternalServiceAuthenticationInterceptor interceptor) {
    this.restClient = builder.baseUrl(properties.getBaseUrl()).requestInterceptor(interceptor).build();
  }

  @Override
  public List<Long> findParticipants(long secretGroupId) {
    try {
      Map<String, List<Long>> response = restClient.get()
          .uri("/internal/secret-group-chats/{groupId}/participants", secretGroupId)
          .retrieve().body(new ParameterizedTypeReference<Map<String, List<Long>>>() {});
      List<Long> userIds = response == null ? null : response.get("userIds");
      return userIds == null ? List.of() : userIds;
    } catch (RuntimeException e) {
      log.warn("Failed to fetch secret group participants, groupId={}", secretGroupId);
      return List.of();
    }
  }

  @Override
  public String destroyPolicyOf(long secretGroupId) {
    try {
      Map<String, String> response = restClient.get()
          .uri("/internal/secret-group-chats/{groupId}/destroy-policy", secretGroupId)
          .retrieve().body(new ParameterizedTypeReference<Map<String, String>>() {});
      String policy = response == null ? null : response.get("destroyPolicy");
      return policy == null ? "off" : policy;
    } catch (RuntimeException e) {
      log.warn("Failed to fetch secret group destroy policy, groupId={}, fallback=off", secretGroupId);
      return "off";
    }
  }

  @Override
  public boolean isOwnerOnlyPost(long secretGroupId) {
    try {
      Map<String, Boolean> response = restClient.get()
          .uri("/internal/secret-group-chats/{groupId}/owner-only-post", secretGroupId)
          .retrieve().body(new ParameterizedTypeReference<Map<String, Boolean>>() {});
      return response != null && Boolean.TRUE.equals(response.get("ownerOnlyPost"));
    } catch (RuntimeException e) {
      log.warn("Failed to fetch secret group owner-only-post, groupId={}, fallback=false", secretGroupId);
      return false;
    }
  }

  @Override
  public long ownerOf(long secretGroupId) {
    try {
      Map<String, Long> response = restClient.get()
          .uri("/internal/secret-group-chats/{groupId}/owner", secretGroupId)
          .retrieve().body(new ParameterizedTypeReference<Map<String, Long>>() {});
      return response == null ? 0L : response.getOrDefault("ownerUserId", 0L);
    } catch (RuntimeException e) {
      log.warn("Failed to fetch secret group owner, groupId={}", secretGroupId);
      return 0L;
    }
  }
}
