package io.openware.im.admin.integration;

import io.openware.im.admin.integration.InternalServiceProperties;
import io.openware.common.dto.AdminListFriendsDto;
import io.openware.common.dto.AdminListMessagesDto;
import io.openware.common.dto.AdminListUsersDto;
import io.openware.common.dto.PageResult;
import io.openware.common.dto.AdminUpdateUserStatusDto;
import io.openware.im.conversation.api.admin.AdminConversationStatsResponse;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.im.message.api.admin.AdminMessageResponse;
import io.openware.im.message.api.admin.AdminMessageStatsResponse;
import io.openware.im.user.api.admin.AdminDeviceTokenResponse;
import io.openware.im.user.api.admin.AdminFriendResponse;
import io.openware.im.user.api.admin.AdminUserDeleteResponse;
import io.openware.im.user.api.admin.AdminUserResponse;
import io.openware.im.user.api.admin.AdminAccountCancellationResponse;
import io.openware.im.user.api.admin.AdminAccountCancellationLogResponse;
import io.openware.im.user.api.admin.AdminUserStatsResponse;
import io.openware.im.user.api.admin.AdminUserStickerResponse;
import io.openware.im.user.api.admin.ChangeUserStatusRequest;
import io.openware.im.user.api.admin.ChangeUserStatusResponse;
import io.openware.im.user.api.authorization.UserProfileSummariesQuery;
import io.openware.im.user.api.authorization.UserProfileSummary;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

@Component
@RequiredArgsConstructor
public class AdminReadClient {
  private static final ParameterizedTypeReference<PageResult<AdminUserResponse>> USER_PAGE = new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<PageResult<AdminFriendResponse>> FRIEND_PAGE = new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<PageResult<AdminUserStickerResponse>> STICKER_PAGE = new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<PageResult<AdminMessageResponse>> MESSAGE_PAGE = new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<List<AdminDeviceTokenResponse>> DEVICE_LIST = new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<PageResult<AdminAccountCancellationResponse>> CANCELLATION_PAGE = new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<List<AdminAccountCancellationLogResponse>> CANCELLATION_LOG_LIST = new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<PageResult<AdminAccountCancellationLogResponse>> CANCELLATION_LOG_PAGE = new ParameterizedTypeReference<>() {};

  private final RestClient.Builder restClientBuilder;
  private final InternalServiceProperties properties;
  private final InternalServiceAuthenticationInterceptor authenticationInterceptor;

  public PageResult<AdminUserResponse> listUsers(AdminListUsersDto query) {
    return userClient().get().uri(builder -> usersUri(builder, "/internal/admin/users", query)).retrieve().body(USER_PAGE);
  }

  public AdminUserResponse getUser(Long id) {
    return userClient().get().uri("/internal/admin/users/{id}", id).retrieve().body(AdminUserResponse.class);
  }

  public ChangeUserStatusResponse updateUserStatus(Long id, Long operatorId, AdminUpdateUserStatusDto request) {
    ChangeUserStatusRequest body = new ChangeUserStatusRequest(1, request.getStatus(), request.getExpectedStatusVersion(),
        request.getIdempotencyKey(), operatorId, request.getReason(), request.getCorrelationId());
    return userClient().put().uri("/internal/admin/users/{id}/status", id).body(body).retrieve()
        .body(ChangeUserStatusResponse.class);
  }

  /**
   * 删除用户（转发到用户服务的内部端点）。
   *
   * <p>用户服务侧会强校验 {@code confirmUsername} 与该账号当前用户名一致，并在管理员账号上返回 409
   * {@code ADMIN_ACCOUNT_UNDELETABLE}；错误码由用户服务原样返回，本方法不做二次翻译。
   */
  public AdminUserDeleteResponse deleteUser(Long id, Long operatorId, String confirmUsername) {
    return userClient().delete().uri(builder -> builder.path("/internal/admin/users/{id}")
        .queryParamIfPresent("operatorId", java.util.Optional.ofNullable(operatorId))
        .queryParam("confirmUsername", confirmUsername).build(id)).retrieve()
        .body(AdminUserDeleteResponse.class);
  }

  public PageResult<AdminFriendResponse> listFriends(AdminListFriendsDto query) {
    return userClient().get().uri(builder -> friendsUri(builder, query)).retrieve().body(FRIEND_PAGE);
  }

  public List<AdminDeviceTokenResponse> listDeviceTokens(Long userId) {
    return userClient().get().uri("/internal/admin/users/{userId}/device-tokens", userId).retrieve().body(DEVICE_LIST);
  }

  public PageResult<AdminDeviceTokenResponse> listAllDeviceTokens(int page, int pageSize, Long userId) {
    return userClient().get().uri(builder -> builder.path("/internal/admin/users/device-tokens")
        .queryParam("page", page).queryParam("pageSize", pageSize)
        .queryParamIfPresent("userId", java.util.Optional.ofNullable(userId)).build()).retrieve().body(
            new ParameterizedTypeReference<PageResult<AdminDeviceTokenResponse>>() {});
  }

  public void disableDeviceToken(Long id) {
    userClient().delete().uri("/internal/admin/users/device-tokens/{id}", id).retrieve().toBodilessEntity();
  }

  public PageResult<java.util.Map<String, Object>> listGroups(int page, int pageSize, String keyword) {
    return conversationClient().get().uri(builder -> builder.path("/internal/admin/groups")
        .queryParam("page", page).queryParam("pageSize", pageSize)
        .queryParamIfPresent("keyword", java.util.Optional.ofNullable(keyword)).build()).retrieve().body(
            new ParameterizedTypeReference<PageResult<java.util.Map<String, Object>>>() {});
  }

  public List<java.util.Map<String, Object>> listGroupMembers(Long id) {
    return conversationClient().get().uri("/internal/admin/groups/{id}/members", id).retrieve().body(
        new ParameterizedTypeReference<List<java.util.Map<String, Object>>>() {});
  }

  public void dissolveGroup(Long id) {
    conversationClient().delete().uri("/internal/admin/groups/{id}", id).retrieve().toBodilessEntity();
  }

  public PageResult<AdminUserStickerResponse> listStickers(int page, int pageSize, Long userId) {
    return userClient().get().uri(builder -> builder.path("/internal/admin/users/stickers").queryParam("page", page)
        .queryParam("pageSize", pageSize).queryParamIfPresent("userId", java.util.Optional.ofNullable(userId)).build())
        .retrieve().body(STICKER_PAGE);
  }

  public void deleteSticker(Long id) {
    userClient().delete().uri("/internal/admin/users/stickers/{id}", id).retrieve().toBodilessEntity();
  }

  public PageResult<AdminMessageResponse> listMessages(AdminListMessagesDto query) {
    return messageClient().get().uri(builder -> messagesUri(builder, query)).retrieve().body(MESSAGE_PAGE);
  }

  /** 管理端删除任意消息（举报处理联动「删消息」）。 */
  public void adminDeleteMessage(String msgId) {
    messageClient().post().uri("/internal/admin/messages/{msgId}/delete", msgId).retrieve().toBodilessEntity();
  }

  public AdminUserStatsResponse userStats(int days) {
    return userClient().get().uri(builder -> builder.path("/internal/admin/users/stats").queryParam("days", days).build())
        .retrieve().body(AdminUserStatsResponse.class);
  }

  public AdminMessageStatsResponse messageStats(int days) {
    return messageClient().get().uri(builder -> builder.path("/internal/admin/messages/stats").queryParam("days", days).build())
        .retrieve().body(AdminMessageStatsResponse.class);
  }

  public AdminConversationStatsResponse conversationStats(int days) {
    return conversationClient().get().uri(builder -> builder.path("/internal/admin/conversations/stats").queryParam("days", days).build())
        .retrieve().body(AdminConversationStatsResponse.class);
  }

  /** 批量查用户资料摘要（昵称/头像），用于举报/违规列表回填；失败返回空映射（回填为空）。 */
  public Map<Long, UserProfileSummary> listProfileSummaries(List<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return Map.of();
    }
    try {
      UserProfileSummary[] summaries = userClient().post()
          .uri("/internal/user/authorizations/profile-summaries")
          .body(new UserProfileSummariesQuery(userIds)).retrieve().body(UserProfileSummary[].class);
      if (summaries == null) {
        return Map.of();
      }
      return java.util.Arrays.stream(summaries)
          .collect(Collectors.toMap(UserProfileSummary::userId, summary -> summary, (left, right) -> left));
    } catch (RuntimeException exception) {
      return Map.of();
    }
  }

  public PageResult<AdminAccountCancellationResponse> listAccountCancellations(int page, int pageSize, Long userId,
      String status, String keyword) {
    return userClient().get().uri(builder -> builder.path("/internal/admin/account-cancellations")
        .queryParam("page", page).queryParam("pageSize", pageSize)
        .queryParamIfPresent("userId", java.util.Optional.ofNullable(userId))
        .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
        .queryParamIfPresent("keyword", java.util.Optional.ofNullable(keyword)).build()).retrieve()
        .body(CANCELLATION_PAGE);
  }

  public List<AdminAccountCancellationLogResponse> listAccountCancellationLogs(Long id) {
    return userClient().get().uri("/internal/admin/account-cancellations/{id}/logs", id).retrieve()
        .body(CANCELLATION_LOG_LIST);
  }

  public PageResult<AdminAccountCancellationLogResponse> searchAccountCancellationLogs(int page, int pageSize,
      Long cancellationId, Long userId, String action) {
    return userClient().get().uri(builder -> builder.path("/internal/admin/account-cancellations/logs")
        .queryParam("page", page).queryParam("pageSize", pageSize)
        .queryParamIfPresent("cancellationId", java.util.Optional.ofNullable(cancellationId))
        .queryParamIfPresent("userId", java.util.Optional.ofNullable(userId))
        .queryParamIfPresent("action", java.util.Optional.ofNullable(action)).build()).retrieve()
        .body(CANCELLATION_LOG_PAGE);
  }

  private RestClient userClient() { return client(properties.getBaseUrl()); }
  private RestClient messageClient() { return client(properties.getMessageBaseUrl()); }
  private RestClient conversationClient() { return client(properties.getConversationBaseUrl()); }
  private RestClient client(String baseUrl) {
    return restClientBuilder.clone().baseUrl(baseUrl).requestInterceptor(authenticationInterceptor).build();
  }
  private static java.net.URI usersUri(UriBuilder builder, String path, AdminListUsersDto query) {
    return builder.path(path).queryParam("page", page(query.getPage())).queryParam("pageSize", pageSize(query.getPageSize()))
        .queryParamIfPresent("username", java.util.Optional.ofNullable(query.getUsername()))
        .queryParamIfPresent("keyword", java.util.Optional.ofNullable(query.getKeyword()))
        .queryParamIfPresent("status", java.util.Optional.ofNullable(query.getStatus())).build();
  }
  private static java.net.URI friendsUri(UriBuilder builder, AdminListFriendsDto query) {
    return builder.path("/internal/admin/users/friends").queryParam("page", page(query.getPage()))
        .queryParam("pageSize", pageSize(query.getPageSize()))
        .queryParamIfPresent("keyword", java.util.Optional.ofNullable(query.getKeyword()))
        .queryParamIfPresent("status", java.util.Optional.ofNullable(query.getStatus()))
        .queryParamIfPresent("groupName", java.util.Optional.ofNullable(query.getGroupName())).build();
  }
  private static java.net.URI messagesUri(UriBuilder builder, AdminListMessagesDto query) {
    return builder.path("/internal/admin/messages").queryParam("page", page(query.getPage()))
        .queryParam("pageSize", pageSize(query.getPageSize())).build();
  }
  private static int page(Integer value) { return value == null ? 1 : value; }
  private static int pageSize(Integer value) { return value == null ? 20 : value; }
}
