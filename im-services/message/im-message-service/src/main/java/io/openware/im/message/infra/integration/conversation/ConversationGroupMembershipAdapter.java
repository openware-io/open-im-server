package io.openware.im.message.infra.integration.conversation;

import io.openware.im.conversation.api.authorization.GroupMessageAuthorizationQuery;
import io.openware.im.conversation.api.authorization.GroupMessageAuthorizationSnapshot;
import io.openware.im.conversation.api.authorization.ConversationMemberIdsResponse;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.im.message.domain.message.port.GroupMembershipPort;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ConversationGroupMembershipAdapter implements GroupMembershipPort {
  private final RestClient restClient;

  public ConversationGroupMembershipAdapter(RestClient.Builder builder, ConversationServiceProperties properties,
      InternalServiceAuthenticationInterceptor interceptor) {
    this.restClient = builder.baseUrl(properties.getBaseUrl()).requestInterceptor(interceptor).build();
  }

  /**
   * 仅成员校验（忽略群状态）：聊天记录/同步等**只读**路径使用。
   * 群解散后成员关系保留，只读路径必须仍可访问（发送路径走 messageRecipientSnapshot，状态感知）。
   */
  @Override
  public boolean isMember(long groupId, long userId) {
    java.util.Map<?, ?> response = restClient.get()
        .uri("/internal/conversation/authorizations/{conversationId}/members/{userId}", groupId, userId)
        .retrieve().body(java.util.Map.class);
    return response != null && Boolean.TRUE.equals(response.get("member"));
  }

  @Override
  public boolean isOwnerOrAdmin(long groupId, long userId) {
    String role = authorization(groupId, userId).memberRole();
    return "OWNER".equals(role) || "ADMIN".equals(role);
  }

  @Override
  public List<Long> findMemberUserIds(long groupId) {
    ConversationMemberIdsResponse response = restClient.get()
        .uri("/internal/conversation/authorizations/{conversationId}/member-ids", groupId)
        .retrieve().body(ConversationMemberIdsResponse.class);
    return response == null ? List.of() : response.userIds();
  }

  @Override
  public GroupMembershipPort.GroupMessageRecipientSnapshot messageRecipientSnapshot(long groupId, long senderId) {
    io.openware.im.conversation.api.authorization.GroupMessageRecipientSnapshot response = restClient.post()
        .uri("/internal/conversation/authorizations/group-message/recipient-snapshot")
        .body(new GroupMessageAuthorizationQuery(groupId, senderId,
            "message-recipient-snapshot-" + groupId + "-" + senderId, Instant.now()))
        .retrieve().body(io.openware.im.conversation.api.authorization.GroupMessageRecipientSnapshot.class);
    if (response == null || response.authorization() == null) {
      return new GroupMembershipPort.GroupMessageRecipientSnapshot(false, List.of());
    }
    return new GroupMembershipPort.GroupMessageRecipientSnapshot(response.authorization().allowed(), response.memberUserIds(),
        response.authorization().denialCode());
  }

  private GroupMessageAuthorizationSnapshot authorization(long groupId, long userId) {
    return restClient.post().uri("/internal/conversation/authorizations/group-message")
        .body(new GroupMessageAuthorizationQuery(groupId, userId, "membership-query-" + groupId + "-" + userId, Instant.now()))
        .retrieve().body(GroupMessageAuthorizationSnapshot.class);
  }
}
