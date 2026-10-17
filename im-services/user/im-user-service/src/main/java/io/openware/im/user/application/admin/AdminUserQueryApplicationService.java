package io.openware.im.user.application.admin;

import io.openware.common.dto.PageResult;
import io.openware.common.enums.FriendStatus;
import io.openware.common.media.api.media.BusinessMediaAccessRequest;
import io.openware.common.enums.UserRole;
import io.openware.common.enums.UserStatus;
import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.im.user.api.admin.AdminDeviceTokenResponse;
import io.openware.im.user.api.admin.AdminFriendResponse;
import io.openware.im.user.api.admin.AdminUserResponse;
import io.openware.im.user.api.admin.AdminUserStatsResponse;
import io.openware.im.user.api.admin.AdminUserStickerResponse;
import io.openware.im.user.application.admin.query.AdminFriendListQuery;
import io.openware.im.user.application.admin.query.AdminUserListQuery;
import io.openware.im.user.domain.account.model.UserAccount;
import io.openware.im.user.domain.account.model.UserAccountStatus;
import io.openware.im.user.domain.account.repository.UserAccountRepository;
import io.openware.im.user.domain.device.model.DeviceToken;
import io.openware.im.user.domain.device.repository.Persisted;
import io.openware.im.user.domain.device.readmodel.DeviceTokenView;
import io.openware.im.user.domain.device.repository.DeviceTokenRepository;
import io.openware.im.user.domain.social.readmodel.FriendRelationView;
import io.openware.im.user.domain.social.repository.FriendRelationRepository;
import io.openware.im.user.domain.sticker.model.UserSticker;
import io.openware.im.user.domain.sticker.readmodel.UserStickerView;
import io.openware.im.user.domain.sticker.repository.UserStickerRepository;
import io.openware.im.user.media.MediaReferenceClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminUserQueryApplicationService {
  private final UserAccountRepository userAccountRepository;
  private final FriendRelationRepository friendRelationRepository;
  private final DeviceTokenRepository deviceTokenRepository;
  private final UserStickerRepository userStickerRepository;
  private final MediaReferenceClient mediaReferences;

  @Transactional(readOnly = true)
  public PageResult<AdminUserResponse> listUsers(AdminUserListQuery query) {
    int page = page(query.page());
    int pageSize = pageSize(query.pageSize());
    String keyword = normalizeKeyword(query.keyword());
    PageResult<UserAccount> result = userAccountRepository.searchForAdmin(query.username(),
        toUserAccountStatus(query.status()), keyword, numericUserId(keyword), page, pageSize);
    Map<String, String> avatarUrls = avatarUrls(result.getItems());
    return mapPage(result, account -> toUserResponse(account, avatarUrls.get(account.getAvatar())));
  }

  @Transactional(readOnly = true)
  public AdminUserResponse detailUser(Long id) {
    UserAccount account = userAccountRepository.findById(id)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "User not found"));
    String avatarUrl = account.getAvatar() == null || account.getAvatar().isBlank()
        ? "" : mediaReferences.accessUrl(account.getId(), account.getAvatar(), account.getId());
    return toUserResponse(account, avatarUrl);
  }

  /** 批量解析当前页用户头像访问 URL，单次调用 support-service，避免逐条 N+1。 */
  private Map<String, String> avatarUrls(List<UserAccount> accounts) {
    List<BusinessMediaAccessRequest> requests = accounts.stream()
        .filter(account -> account.getAvatar() != null && !account.getAvatar().isBlank())
        .map(account -> new BusinessMediaAccessRequest(
            account.getAvatar(), "user_profile", String.valueOf(account.getId())))
        .toList();
    return mediaReferences.accessUrls(requests);
  }

  @Transactional(readOnly = true)
  public PageResult<AdminFriendResponse> listFriends(AdminFriendListQuery query) {
    int page = page(query.page());
    int pageSize = pageSize(query.pageSize());
    String keyword = normalizeKeyword(query.keyword());
    List<Long> matchedUserIds = keyword == null ? List.of() : userAccountRepository.findIdsMatchingKeyword(keyword);
    PageResult<FriendRelationView> result = friendRelationRepository.searchForAdmin(toFriendStatus(query.status()),
        query.groupName(), keyword, matchedUserIds, numericUserId(keyword), page, pageSize);
    Map<Long, UserAccount> accounts = accountsOf(result.getItems().stream()
        .flatMap(relation -> Stream.of(relation.userId(), relation.friendId()))
        .filter(java.util.Objects::nonNull).distinct().toList());
    return mapPage(result, relation -> toFriendResponse(relation, accounts));
  }

  @Transactional(readOnly = true)
  public List<AdminDeviceTokenResponse> deviceTokens(Long userId) {
    return deviceTokenRepository.findEnabledByUserId(userId).stream()
        .map(AdminUserQueryApplicationService::viewOf)
        .map(this::toDeviceTokenResponse).toList();
  }

  /** 供 access-ws 离线推送使用，按原样返回明文 token。 */
  @Transactional(readOnly = true)
  public List<Map<String, String>> pushDeviceTokens(Long userId) {
    return deviceTokenRepository.findEnabledByUserId(userId).stream()
        .map(row -> Map.of("token", row.aggregate().getToken(),
            "platform", row.aggregate().getPlatform().getValue(),
            "pushProvider", row.aggregate().getPushProvider().getValue()))
        .toList();
  }

  /** 载体 → 只读投影：供只需要展示字段的列表复用同一响应装配。 */
  private static DeviceTokenView viewOf(Persisted<DeviceToken> row) {
    DeviceToken token = row.aggregate();
    return new DeviceTokenView(token.getId(), token.getUserId(), token.getToken(), token.getPushProvider(),
        token.getPlatform(), token.getDeviceId(), token.getEnabled(), row.audit().createdAt(),
        row.audit().updatedAt());
  }

  @Transactional(readOnly = true)
  public PageResult<AdminDeviceTokenResponse> allDeviceTokens(int page, int pageSize, Long userId) {
    PageResult<DeviceTokenView> result = deviceTokenRepository.findEnabledForAdmin(userId, page, pageSize);
    return mapPage(result, this::toDeviceTokenResponse);
  }

  @Transactional
  public void disableDeviceToken(Long id) {
    DeviceToken token = deviceTokenRepository.findById(id)
        .map(Persisted::aggregate)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Device token not found"));
    token.disable();
    deviceTokenRepository.save(token);
  }

  @Transactional(readOnly = true)
  public PageResult<AdminUserStickerResponse> stickers(int page, int pageSize, Long userId) {
    PageResult<UserStickerView> result = userStickerRepository.findForAdmin(userId, page, pageSize);
    Map<Long, UserAccount> users = accountsOf(result.getItems().stream().map(UserStickerView::userId)
        .filter(java.util.Objects::nonNull).distinct().toList());
    return mapPage(result, sticker -> toStickerResponse(sticker, users.get(sticker.userId())));
  }

  @Transactional
  public void deleteSticker(Long id) {
    UserSticker sticker = userStickerRepository.findById(id)
        .map(row -> row.aggregate())
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Sticker not found"));
    userStickerRepository.deleteById(id);
    userStickerRepository.releaseSlots(sticker.getUserId(), 1);
  }

  @Transactional(readOnly = true)
  public AdminUserStatsResponse stats(int days) {
    LocalDateTime since = days <= 0 ? LocalDate.now(Clock.systemUTC()).atStartOfDay()
        : LocalDateTime.now(Clock.systemUTC()).minusDays(days);
    return new AdminUserStatsResponse(userAccountRepository.countAll(), userAccountRepository.countCreatedSince(since),
        friendRelationRepository.countAll(), friendRelationRepository.countCreatedSince(since),
        userAccountRepository.dailyCounts(since), friendRelationRepository.dailyCounts(since));
  }

  private AdminUserResponse toUserResponse(UserAccount account, String avatarUrl) {
    return new AdminUserResponse(account.getId(), account.getUsername(), account.getNickname(),
        avatarUrl, account.getEmail(), account.getPhone(), account.getSignature(),
        UserStatus.valueOf(account.getStatus().name()), account.getStatusVersion(),
        UserRole.valueOf(account.getRole().name()), account.getCreatedAt(), account.getUpdatedAt());
  }

  private AdminFriendResponse toFriendResponse(FriendRelationView relation, Map<Long, UserAccount> accounts) {
    UserAccount user = accounts.get(relation.userId());
    UserAccount friend = accounts.get(relation.friendId());
    return new AdminFriendResponse(relation.id(), relation.userId(), user == null ? null : user.getNickname(),
        relation.friendId(), friend == null ? null : friend.getNickname(), relation.remark(),
        relation.groupName(), FriendStatus.valueOf(relation.status().name()), relation.createdAt());
  }

  private AdminDeviceTokenResponse toDeviceTokenResponse(DeviceTokenView row) {
    return new AdminDeviceTokenResponse(row.id(), row.userId(), tokenFingerprint(row.token()),
        row.provider(), row.platform(), row.deviceId(), row.enabled(), row.createdAt(),
        row.updatedAt());
  }

  private AdminUserStickerResponse toStickerResponse(UserStickerView sticker, UserAccount user) {
    return new AdminUserStickerResponse(sticker.id(), sticker.userId(),
        user == null ? null : user.getUsername(), user == null ? null : user.getNickname(),
        sticker.url(), sticker.thumbnail(), sticker.sortOrder(), sticker.createdAt());
  }

  private Map<Long, UserAccount> accountsOf(List<Long> userIds) {
    if (userIds.isEmpty()) {
      return Map.of();
    }
    return userAccountRepository.findByIds(userIds).stream()
        .collect(Collectors.toMap(UserAccount::getId, Function.identity(), (a, b) -> a));
  }

  private static <S, T> PageResult<T> mapPage(PageResult<S> source, Function<S, T> converter) {
    return PageResult.<T>builder().items(source.getItems().stream().map(converter).toList()).total(source.getTotal())
        .page(source.getPage()).pageSize(source.getPageSize()).build();
  }

  private static int page(Integer page) {
    return page == null ? 1 : page;
  }

  private static int pageSize(Integer pageSize) {
    return pageSize == null ? 20 : pageSize;
  }

  private static String normalizeKeyword(String keyword) {
    return (keyword == null || keyword.isBlank()) ? null : keyword.trim();
  }

  private static Long numericUserId(String keyword) {
    if (keyword == null) {
      return null;
    }
    try {
      return Long.parseLong(keyword);
    // degradation: lookup helper returns null when the optional value is absent; the caller branches on it
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static UserAccountStatus toUserAccountStatus(UserStatus status) {
    return status == null ? null : UserAccountStatus.valueOf(status.name());
  }

  private static io.openware.im.user.domain.social.model.FriendStatus toFriendStatus(FriendStatus status) {
    return status == null ? null : io.openware.im.user.domain.social.model.FriendStatus.valueOf(status.name());
  }

  private static String tokenFingerprint(String token) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
        }