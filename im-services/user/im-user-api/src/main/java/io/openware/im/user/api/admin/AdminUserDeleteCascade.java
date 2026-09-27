package io.openware.im.user.api.admin;

/**
 * 后台删除 IM 用户的级联清理计数（{@code DELETE /internal/admin/users/{id}} 返回体的 {@code cascade} 字段）。
 *
 * <p>口径：业务数据硬删除，审计记录独立保留：
 * <ul>
 *   <li>清空账号私有关联数据：设备令牌/登录态/设备密钥、个人设置、密保、收藏、好友关系与申请、群/频道成员行、密聊参与行；</li>
 *   <li>不删除统一账号模型、SaaS 客户、订单、支付或审计数据；这些字段保留为 0，IM 仅通过用户行缺失表达解绑。</li>
 *   <li>消息本体和用户相关投影由硬删除事件清理；审计记录不在级联范围内。</li>
 *   <li>{@code account} 恒为 1：账号主记录物理删除；{@code unifiedAccounts} 是物理删除的
 *       {@code idt_account} 行数。</li>
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
