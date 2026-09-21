package com.gvchat.im.user.application.profile;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.im.user.application.profile.command.ChangePasswordCommand;
import com.gvchat.im.user.application.profile.command.DeleteAccountCommand;
import com.gvchat.im.user.application.profile.command.UpdateProfileCommand;
import com.gvchat.im.user.application.profile.result.UserProfileResult;
import com.gvchat.im.user.api.authorization.UserProfileSummariesQuery;
import com.gvchat.im.user.api.authorization.UserProfileSummary;
import com.gvchat.im.user.domain.account.model.UserAccount;
import com.gvchat.im.user.domain.account.model.UserAccountStatus;
import com.gvchat.im.user.domain.account.port.PasswordHasher;
import com.gvchat.im.user.domain.account.repository.UserAccountRepository;
import com.gvchat.im.user.domain.account.port.UserStatusEventOutbox;
import com.gvchat.im.user.domain.account.port.UserProfileEventOutbox;
import com.gvchat.im.user.domain.account.event.UserAuthenticationInvalidated;
import com.gvchat.im.user.domain.account.event.UserChatRecordsPurged;
import com.gvchat.im.user.domain.account.event.UserProfileChanged;
import com.gvchat.im.user.domain.account.model.UserAuthenticationSnapshot;
import com.gvchat.im.user.domain.account.port.UserAuthenticationProjectionPort;
import com.gvchat.im.user.media.MediaReferenceClient;
import java.time.Instant;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileApplicationService {
  private static final int SEARCH_LIMIT = 20;

  private final UserAccountRepository userAccountRepository;
  private final PasswordHasher passwordHasher;
  private final UserStatusEventOutbox userStatusEventOutbox;
  private final UserProfileEventOutbox userProfileEventOutbox;
  private final UserAuthenticationProjectionPort authenticationUserProjection;
  private final MediaReferenceClient mediaReferences;

  @Transactional(readOnly = true)
  public UserProfileResult getProfile(Long userId) {
    return toResult(requireAccount(userId));
  }

  /** 他人视角资料：仅本人可见 email/phone，他人查看时脱敏（S3 敏感数据默认脱敏展示）。 */
  @Transactional(readOnly = true)
  public UserProfileResult getProfileForViewer(Long viewerId, Long targetUserId) {
    UserAccount account = requireAccount(targetUserId);
    return java.util.Objects.equals(viewerId, targetUserId) ? toResult(account) : toMaskedResult(account);
  }

  @Transactional(readOnly = true)
  public List<UserProfileResult> searchProfiles(String keyword) {
    String normalizedKeyword = keyword == null ? "" : keyword.trim();
    if (normalizedKeyword.isEmpty()) {
      return List.of();
    }
    return userAccountRepository.search(normalizedKeyword, SEARCH_LIMIT).stream().map(this::toMaskedResult).toList();
  }

  @Transactional(readOnly = true)
  public List<UserProfileSummary> findProfileSummaries(UserProfileSummariesQuery query) {
    List<Long> userIds = query.userIds() == null ? List.of() : query.userIds().stream()
        .filter(java.util.Objects::nonNull).filter(userId -> userId > 0).distinct().toList();
    return userAccountRepository.findByIds(userIds).stream()
        .map(account -> new UserProfileSummary(account.getId(), account.getUsername(), account.getNickname(),
            mediaReferences.accessUrl(account.getId(), account.getAvatar(), account.getId())))
        .toList();
  }

  /** 批量查询用户全局禁言状态（userId -> 是否 muted）。 */
  @Transactional(readOnly = true)
  public java.util.Map<Long, Boolean> findMuteStatus(List<Long> userIds) {
    List<Long> ids = userIds == null ? List.of() : userIds.stream()
        .filter(java.util.Objects::nonNull).distinct().toList();
    return userAccountRepository.findByIds(ids).stream()
        .collect(java.util.stream.Collectors.toMap(
            account -> account.getId(),
            account -> account.getStatus() == UserAccountStatus.MUTED,
            (left, right) -> left));
  }

  @Transactional
  public UserProfileResult updateProfile(Long userId, UpdateProfileCommand command) {
    UserAccount account = requireAccount(userId);
    String previousAvatar = account.getAvatar();
    if (command.avatar() != null && !command.avatar().isBlank()) {
      mediaReferences.authorizeAndBind(userId, command.avatar());
    }
    account.updateProfile(
        command.nickname(), command.avatar(), command.email(), command.phone(), command.signature(),
        LocalDateTime.now());
    UserProfileResult result = toResult(userAccountRepository.save(account));
    if (command.avatar() != null && !java.util.Objects.equals(previousAvatar, command.avatar())) {
      mediaReferences.unbind(previousAvatar, userId);
    }
    userProfileEventOutbox.append(new UserProfileChanged(UUID.randomUUID().toString(), account.getId(),
        account.getNickname(), account.getAvatar(), account.getPhone(), Instant.now()));
    return result;
  }

  @Transactional
  public void changePassword(Long userId, ChangePasswordCommand command) {
    UserAccount account = requireAccount(userId);
    if (!passwordHasher.matches(command.currentPassword(), account.getPasswordHash())) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Password incorrect");
    }
    // 新密码不得与旧密码相同：否则用户会在无任何变化的情况下被登出所有设备，
    // 且无法察觉自己其实没有修改成功（2026-09-10 修复）。
    if (passwordHasher.matches(command.newPassword(), account.getPasswordHash())) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "New password must differ from the current password");
    }
    account.changePassword(passwordHasher.hash(command.newPassword()), LocalDateTime.now());
    invalidateAuthentication(account);
  }

  @Transactional
  public void deleteAccount(Long userId, DeleteAccountCommand command) {
    UserAccount account = requireAccount(userId);
    if (account.getStatus() == UserAccountStatus.DISABLED) {
      throw new ApiException(HttpStatusCodes.GONE, "Account already cancelled");
    }
    if (!passwordHasher.matches(command.password(), account.getPasswordHash())) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Password incorrect");
    }
    byte[] randomBytes = new byte[32];
    new SecureRandom().nextBytes(randomBytes);
    String replacementUsername = "deleted_" + userId + "_" + System.currentTimeMillis();
    if (replacementUsername.length() > 64) {
      replacementUsername = replacementUsername.substring(0, 64);
    }
    account.cancel(passwordHasher.hash(HexFormat.of().formatHex(randomBytes)), replacementUsername, LocalDateTime.now());
    invalidateAuthentication(account);
    // 账号注销（软删除）：广播聊天记录清理事件，让会话/消息服务级联清除该用户的群成员、密聊、频道订阅等关联数据，
    // 避免群成员列表残留「用户xxx」这类已注销账号的悬空引用。
    userStatusEventOutbox.append(new UserChatRecordsPurged(UUID.randomUUID().toString(), userId, Instant.now()));
  }

  private UserAccount requireAccount(Long userId) {
    return userAccountRepository.findById(userId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "User not found"));
  }

  private UserProfileResult toResult(UserAccount account) {
    return new UserProfileResult(
        account.getId(), account.getUsername(), account.getNickname(),
        mediaReferences.accessUrl(account.getId(), account.getAvatar(), account.getId()), account.getEmail(),
        account.getPhone(), account.getSignature(), account.getStatus().name().toLowerCase(),
        account.getRole().name().toLowerCase(), account.getCreatedBy(), account.getCreatedAt(),
        account.getUpdatedBy(), account.getUpdatedAt());
  }

  private UserProfileResult toMaskedResult(UserAccount account) {
    return new UserProfileResult(
        account.getId(), account.getUsername(), account.getNickname(),
        mediaReferences.accessUrl(account.getId(), account.getAvatar(), account.getId()),
        maskEmail(account.getEmail()), maskPhone(account.getPhone()), account.getSignature(),
        account.getStatus().name().toLowerCase(), account.getRole().name().toLowerCase(),
        account.getCreatedBy(), account.getCreatedAt(), account.getUpdatedBy(), account.getUpdatedAt());
  }

  private String maskEmail(String email) {
    if (email == null || email.isBlank()) return email;
    int at = email.indexOf('@');
    if (at <= 0) return "***";
    return email.charAt(0) + "***" + email.substring(at);
  }

  private String maskPhone(String phone) {
    if (phone == null || phone.isBlank()) return phone;
    String digits = phone.replaceAll("[^0-9]", "");
    if (digits.length() <= 7) return "***";
    return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
  }

  private void invalidateAuthentication(UserAccount account) {
    userAccountRepository.save(account);
    authenticationUserProjection.save(new UserAuthenticationSnapshot(
        account.getId(),
        account.getUsername(),
        account.getRole(),
        account.getStatus() != UserAccountStatus.DISABLED,
        account.getStatusVersion()));
    userStatusEventOutbox.append(new UserAuthenticationInvalidated(
        UUID.randomUUID().toString(), account.getId(), account.getStatusVersion(), null, Instant.now()));
  }
}
