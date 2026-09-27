package io.openware.im.user.application.admin;

import io.openware.common.exception.ApiException;
import io.openware.im.user.api.admin.AdminUserDeleteCascade;
import io.openware.im.user.api.admin.AdminUserDeleteResponse;
import io.openware.im.user.domain.account.event.UserAuthenticationInvalidated;
import io.openware.im.user.domain.account.event.UserChatRecordsPurged;
import io.openware.im.user.domain.account.event.UserDataWipeRequested;
import io.openware.im.user.domain.account.model.UserAccount;
import io.openware.im.user.domain.account.model.UserAccountRole;
import io.openware.im.user.domain.account.port.UserStatusEventOutbox;
import io.openware.im.user.domain.account.repository.UserAccountRepository;
import io.openware.im.user.infra.persistence.admin.AdminUserCascadeMapper;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IM 后台「删除用户」：守卫 → 级联清理 → 账号硬删除，全程单个事务。
 *
 * <p>守卫顺序（fail-closed，任一失败整体不做任何写操作）：
 * <ol>
 *   <li>账号不存在 → 404 {@code USER_NOT_FOUND}（重复删除同一 id 也走这里，幂等不报 500）；</li>
 *   <li>管理员账号 → 409 {@code ADMIN_ACCOUNT_UNDELETABLE}（{@code user.role='admin'}，
 *       含平台/后台自建管理员；管理员账号在 IM 后台一律不可删）；</li>
 *   <li>{@code confirmUsername} 与账号当前用户名不一致 → 400 {@code USERNAME_CONFIRM_MISMATCH}
 *       （服务端强校验，不依赖前端）。</li>
 * </ol>
 *
 * <p>删除口径：业务数据硬删除，审计记录由后台审计服务保留：
 * <ul>
 *   <li>账号私有关联数据物理删除（设备令牌/登录态/设备密钥、个人设置、密保、收藏、好友关系与申请、
 *       群/频道成员行、密聊参与行）；</li>
 *   <li>用户主记录硬删除；审计记录不参与级联删除，保留完整删除证据；</li>
 *   <li>不得跨业务删除 SaaS 身份、客户、订单、支付或审计数据；这些域仅根据 IM 用户行缺失展示解绑状态。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserDeletionApplicationService {

  private final UserAccountRepository userAccountRepository;
  private final AdminUserCascadeMapper cascadeMapper;
  private final UserStatusEventOutbox userStatusEventOutbox;

  @Transactional
  public AdminUserDeleteResponse deleteUser(Long userId, String confirmUsername, Long operatorId) {
    UserAccount account = userAccountRepository.findById(userId)
        .orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "账号不存在或已被删除"));

    if (account.getRole() == UserAccountRole.ADMIN) {
      throw new ApiException(409, "ADMIN_ACCOUNT_UNDELETABLE", "管理员账号不允许删除");
    }

    String expectedUsername = account.getUsername();
    if (confirmUsername == null || !confirmUsername.trim().equals(expectedUsername)) {
      throw new ApiException(400, "USERNAME_CONFIRM_MISMATCH", "输入的用户名与当前账号不一致，未执行删除");
    }

    AdminUserDeleteCascade imCascade = purgeCascade(userId);

    // 先使现有认证失效，再硬删除账号主记录；审计由 admin 服务异步写入并保留。
    userStatusEventOutbox.append(new UserAuthenticationInvalidated(
        UUID.randomUUID().toString(), userId, account.getStatusVersion(), null, Instant.now()));
    // 用户业务数据统一硬删除：通知消息/会话域删除该用户的消息、投影和成员关系。
    userStatusEventOutbox.append(new UserChatRecordsPurged(
        UUID.randomUUID().toString(), userId, Instant.now()));
    userStatusEventOutbox.append(new UserDataWipeRequested(
        UUID.randomUUID().toString(), userId, Instant.now()));
    if (userAccountRepository.hardDelete(userId) != 1) {
      throw new ApiException(409, "USER_DELETE_CONFLICT", "账号删除并发冲突，请重试");
    }

    AdminUserDeleteCascade cascade = imCascade;
    log.info("后台删除 IM 用户完成, userId={}, username={}, operatorId={}, cascade={}",
        userId, expectedUsername, operatorId, cascade);
    return new AdminUserDeleteResponse(true, expectedUsername, null, false, cascade);
  }

  /**
   * 级联清理：逐表删除并回执行数。
   *
   * <p>顺序刻意放在主记录删除之前：先把用户名关联的私有关联清掉，再硬删除账号；
   * 事务内任一步失败整体回滚，不会留下半删状态。
   */
  private AdminUserDeleteCascade purgeCascade(Long userId) {
    long deviceTokens = cascadeMapper.deleteDeviceTokens(userId);
    long deviceSessions = cascadeMapper.deleteDeviceSessions(userId);
    long deviceKeys = cascadeMapper.deleteDeviceKeys(userId);
    long notificationSettings = cascadeMapper.deleteNotificationSettings(userId);
    long privacySettings = cascadeMapper.deletePrivacySettings(userId);
    long securityQuestions = cascadeMapper.deleteSecurityQuestions(userId);
    long favorites = cascadeMapper.deleteFavorites(userId);
    long friendRelations = cascadeMapper.deleteFriendRelations(userId);
    long friendRequests = cascadeMapper.deleteFriendRequests(userId);
    long groupMembers = cascadeMapper.deleteGroupMembers(userId);
    long channelSubscriptions = cascadeMapper.deleteChannelSubscriptions(userId);
    long secretChats = cascadeMapper.deleteSecretChats(userId);
    long secretGroupMembers = cascadeMapper.deleteSecretGroupMembers(userId);
    long stickers = cascadeMapper.deleteStickers(userId) + cascadeMapper.deleteStickerQuota(userId);
    long statusOperations = cascadeMapper.deleteStatusOperations(userId);
    cascadeMapper.deleteOutboxEvents(userId);
    // 统一身份、SaaS 与审计属于其他业务域，严禁在 IM 删除事务内级联删除。
    return new AdminUserDeleteCascade(deviceTokens, deviceSessions, deviceKeys, notificationSettings,
        securityQuestions, favorites, friendRelations, friendRequests, groupMembers, channelSubscriptions,
        secretChats, secretGroupMembers, stickers, privacySettings, statusOperations, 0L, 0L, 0L, false, null,
        1L);
  }

}
