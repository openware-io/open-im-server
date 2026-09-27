package io.openware.im.user.api.admin;

/**
 * 后台删除 IM 用户的结果（{@code DELETE /internal/admin/users/{id}}）。
 *
 * @param deleted          是否已删除（成功即 true；账号不存在走 404，不返回本结构）
 * @param username         被删除账号删除前的用户名（审计与二次确认展示用）
 * @param tombstoneUsername 兼容字段，硬删除时恒为 {@code null}
 * @param messagesPreserved 兼容字段，硬删除时为 {@code false}
 * @param cascade          级联清理计数
 */
public record AdminUserDeleteResponse(
    boolean deleted,
    String username,
    String tombstoneUsername,
    boolean messagesPreserved,
    AdminUserDeleteCascade cascade) {
}
