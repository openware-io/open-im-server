package com.gvchat.im.user.application.admin;

import com.gvchat.common.exception.ApiException;
import com.gvchat.im.user.api.admin.AdminUserDeleteCascade;
import com.gvchat.im.user.api.admin.AdminUserDeleteResponse;
import com.gvchat.im.user.domain.account.event.UserAuthenticationInvalidated;
import com.gvchat.im.user.domain.account.model.UserAccount;
import com.gvchat.im.user.domain.account.model.UserAccountRole;
import com.gvchat.im.user.domain.account.port.PasswordHasher;
import com.gvchat.im.user.domain.account.port.UserStatusEventOutbox;
import com.gvchat.im.user.domain.account.repository.UserAccountRepository;
import com.gvchat.im.user.integration.UnifiedAccountPurgeClient;
import com.gvchat.im.user.infra.persistence.admin.AdminUserCascadeMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IM 后台「删除用户」：守卫 → 级联清理 → 账号墓碑化，全程单个事务。
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
 * <p>删除口径（门店 2026-09-20 确认）：
 * <ul>
 *   <li>账号私有关联数据物理删除（设备令牌/登录态/设备密钥、个人设置、密保、收藏、好友关系与申请、
 *       群/频道成员行、密聊参与行）；</li>
 *   <li><b>IM 消息本体保留</b>：不删 {@code msg_message} 等表，也不投递「聊天记录清理」事件，
 *       避免把对方的聊天记录删出空洞；</li>
 *   <li>统一账号模型（SaaS 身份域）里的 IM 落点同步清理：物理删 {@code idt_login_identity} 与
 *       {@code idt_oauth_link}；{@code idt_account} **只在它是客户账号且已无其它落点（孤儿）时才删**，
 *       员工 / 平台运营账号本体保留（删除后该员工账号处于「未绑定 IM」，等后台换绑新的 IM 账号）。
 *       该调用是事务内**最后一步**，远端失败即整体回滚，不留半删状态；</li>
 *   <li>账号主记录**墓碑化而非物理删除**：用户名置为 {@code deleted_<id>_<hash>}、清空个人可识别信息并置为
 *       disabled。为什么不做物理硬删：
 *       <ol>
 *         <li>客户档案（{@code platform-customer-service}）正是按该用户名形态或用户行缺失判定
 *             「IM 账号已删除」，保留墓碑行让这条既有判据继续成立；</li>
 *         <li>用户名唯一索引被释放，原用户名可再次注册，而历史消息/群成员里的用户 ID 仍能解析到「已删除用户」，
 *             不会留下悬空引用；</li>
 *         <li>与用户自助注销（{@code AccountCancellationProcessor}）保持同一形态，避免两套「已删除」口径。</li>
 *       </ol></li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserDeletionApplicationService {

  private final UserAccountRepository userAccountRepository;
  private final AdminUserCascadeMapper cascadeMapper;
  private final PasswordHasher passwordHasher;
  private final UserStatusEventOutbox userStatusEventOutbox;
  private final UnifiedAccountPurgeClient unifiedAccountPurgeClient;

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

    // 账号墓碑化：释放原用户名（唯一索引），清空个人可识别信息，并让该账号的所有会话失效。
    LocalDateTime now = LocalDateTime.now();
    String tombstoneUsername = tombstoneUsername(userId);
    account.cancel(passwordHasher.hash(UUID.randomUUID().toString()), tombstoneUsername, now);
    userAccountRepository.save(account);
    userStatusEventOutbox.append(new UserAuthenticationInvalidated(
        UUID.randomUUID().toString(), userId, account.getStatusVersion(), null, Instant.now()));

    // 事务内最后一步：清理统一账号模型的 IM 落点。远端失败 → 整体回滚（不留半删状态）。
    UnifiedAccountPurgeClient.PurgeResult unified = unifiedAccountPurgeClient.purgeImAccount(expectedUsername);

    AdminUserDeleteCascade cascade = withUnifiedAccounts(imCascade, unified);
    log.info("后台删除 IM 用户完成, userId={}, username={}, operatorId={}, cascade={}",
        userId, expectedUsername, operatorId, cascade);
    return new AdminUserDeleteResponse(true, expectedUsername, tombstoneUsername, true, cascade);
  }

  /**
   * 合并统一账号清理结果（IM 侧计数不变，补上登录标识/OAuth 绑定/账号本体三项）。
   *
   * <p>{@code unifiedAccountRetained=true} 表示统一账号本体被保留（员工/平台运营账号，或客户账号还有其他
   * 登录方式）：IM 身份已解除，等后台换绑新的 IM 账号即可（审计详情据此区分「删干净」与「只解绑」）。
   */
  private static AdminUserDeleteCascade withUnifiedAccounts(AdminUserDeleteCascade im,
      UnifiedAccountPurgeClient.PurgeResult unified) {
    return new AdminUserDeleteCascade(im.deviceTokens(), im.deviceSessions(), im.deviceKeys(),
        im.notificationSettings(), im.securityQuestions(), im.favorites(), im.friendRelations(),
        im.friendRequests(), im.groupMembers(), im.channelSubscriptions(), im.secretChats(),
        im.secretGroupMembers(), im.stickers(), im.privacySettings(), im.statusOperations(),
        unified.loginIdentities(), unified.oauthLinks(), unified.deletedAccount() ? 1L : 0L,
        unified.accountType() != null && !unified.deletedAccount(), unified.accountType(), im.account());
  }

  /**
   * 级联清理：逐表删除并回执行数。
   *
   * <p>顺序刻意放在墓碑化**之前**：先把用户名释放相关的私有关联清掉，再改用户名；
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
    // 统一账号模型三项（loginIdentities/oauthLinks/unifiedAccounts）由事务末的统一账号清理补齐。
    return new AdminUserDeleteCascade(deviceTokens, deviceSessions, deviceKeys, notificationSettings,
        securityQuestions, favorites, friendRelations, friendRequests, groupMembers, channelSubscriptions,
        secretChats, secretGroupMembers, stickers, privacySettings, statusOperations, 0L, 0L, 0L, false, null,
        1L);
  }

  /** 生成墓碑用户名：形如 {@code deleted_<id>_<hash>}，与用户自助注销保持同一形态。 */
  private static String tombstoneUsername(Long userId) {
    return "deleted_" + userId + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }
}
