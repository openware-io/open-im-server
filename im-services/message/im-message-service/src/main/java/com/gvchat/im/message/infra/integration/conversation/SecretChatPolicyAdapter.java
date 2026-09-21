package com.gvchat.im.message.infra.integration.conversation;

import com.gvchat.infrastructure.security.InternalServiceAuthenticationInterceptor;
import com.gvchat.im.message.domain.secretmessage.port.SecretChatPolicyPort;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 私密聊天策略适配器：调用会话服务内部接口读取销毁策略；失败按 off 兜底，不让销毁引擎误伤。 */
@Component
@Slf4j
public class SecretChatPolicyAdapter implements SecretChatPolicyPort {
  private final RestClient restClient;

  public SecretChatPolicyAdapter(RestClient.Builder builder, ConversationServiceProperties properties,
      InternalServiceAuthenticationInterceptor interceptor) {
    this.restClient = builder.baseUrl(properties.getBaseUrl()).requestInterceptor(interceptor).build();
  }

  @Override
  public String destroyPolicyOf(long secretChatId) {
    try {
      Map<String, String> response = restClient.get()
          .uri("/internal/secret-chats/{secretChatId}/destroy-policy", secretChatId)
          .retrieve().body(new ParameterizedTypeReference<Map<String, String>>() {});
      String policy = response == null ? null : response.get("destroyPolicy");
      return policy == null ? "off" : policy;
    } catch (RuntimeException e) {
      log.warn("Failed to fetch secret chat destroy policy, secretChatId={}, fallback=off", secretChatId);
      return "off";
    }
  }
}
