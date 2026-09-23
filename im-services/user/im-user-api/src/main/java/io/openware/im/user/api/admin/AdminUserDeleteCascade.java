package io.openware.im.user.api.admin;

/**
 * 后台删除 IM 用户的级联清理计数（{@code DELETE /internal/admin/users/{id}} 返回体的 {@code cascade} 字段）。
 *
 * <p>口径（门店 2026-09-20 确认）：
 * <ul>
 *   <li>清空账号私有关联数据：设备令牌/登录态/设备密钥、个人设置、密保、收藏、好友关系与申请、群/频道成员行、密聊参与行；</li>
 *   <li>清空统一账号模型里的 IM 落点：{@code idt_login_identity}、{@code idt_oauth_link}，
 *       以及清完后已无任何落点的**客户**孤儿 {@code idt_account}；员工 / 平台运营账号本体一律保留
 *       （{@code unifiedAccountRetained=true}，删除后该账号处于「未绑定 IM」，等后台换绑新的 IM 账号）；</li>
 *   <li><b>消息本体（{@code msg_message} 等 {@code msg_*} 表）一律保留</b>，不回删——避免对方聊天记录出现空洞；</li>
 *   <li>{@code account} 恒为 1：账号主记录按 {@code deleted_<id>_<hash>} 墓碑化（保留行，供客户档案判定
 *       「IM 账号已删除」）；{@code unifiedAccounts} 才是物理删除的 {@code idt_account} 行数。</li>
 * </ul>
 */
public record AdminUserDeleteCascade(
    long deviceTokens,
    long deviceSessions,
    long deviceKeys,
    long notificationSettings,
    long securityQuestions,
    long favorites,
    long friendRelations,
    long friendRequests,
    long groupMembers,
    long channelSubscriptions,
    long secretChats,
    long secretGroupMembers,
    long stickers,
    long privacySettings,
    long statusOperations,
    long loginIdentities,
    long oauthLinks,
    long unifiedAccounts,
    boolean unifiedAccountRetained,
    String unifiedAccountType,
    long account) {
}
