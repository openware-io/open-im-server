package com.gvchat.im.message.infra.integration.conversation;

import com.gvchat.infrastructure.security.InternalServiceAuthenticationInterceptor;
import com.gvchat.im.message.domain.message.port.ChannelMembershipPort;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ChannelMembershipAdapter implements ChannelMembershipPort {
  private final RestClient restClient;

  public ChannelMembershipAdapter(RestClient.Builder builder, ConversationServiceProperties properties,
      InternalServiceAuthenticationInterceptor interceptor) {
    this.restClient = builder.baseUrl(properties.getBaseUrl()).requestInterceptor(interceptor).build();
  }

  @Override
  public boolean isOwner(long channelId, long userId) {
    Map<String, Boolean> response = restClient.get()
        .uri("/internal/channels/{channelId}/owner?userId={userId}", channelId, userId)
        .retrieve().body(new ParameterizedTypeReference<Map<String, Boolean>>() {});
    return response != null && Boolean.TRUE.equals(response.get("owner"));
  }

  @Override
  public boolean isSubscribed(long channelId, long userId) {
    Map<String, Boolean> response = restClient.get()
        .uri("/internal/channels/{channelId}/subscribed?userId={userId}", channelId, userId)
        .retrieve().body(new ParameterizedTypeReference<Map<String, Boolean>>() {});
    return response != null && Boolean.TRUE.equals(response.get("subscribed"));
  }

  @Override
  public List<Long> listSubscriberUserIds(long channelId) {
    List<Long> response = restClient.get()
        .uri("/internal/channels/{channelId}/subscriber-ids", channelId)
        .retrieve().body(new ParameterizedTypeReference<List<Long>>() {});
    return response == null ? List.of() : response;
  }
}
