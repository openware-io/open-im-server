package io.openware.platform.tenant.domain.iam.model;

import java.util.List;
import java.util.Map;

/**
 * IAM 权限目录矩阵快照（读模型）。
 *
 * <p>一次请求内同时取得「权限字典 + 全局角色 + 角色-权限授予关系」，保证列表与矩阵来自同一时刻，
 * 不会出现“列表已刷新、矩阵还是上一版”的错位。本类型只用于只读查询与缓存，不是聚合。
 *
 * @param permissions      全部可用权限点（稳定顺序）
 * @param roles            全局角色定义（tenant_id IS NULL 的预置/平台自定义角色）
 * @param rolePermissionIds 角色标识 → 已授予权限标识；只包含 {@code roles} 中出现的角色
 * @param version          由 IAM 表事实派生的数据版本，缓存以此做一致性键
 * @param generatedAt      快照生成时间（ISO-8601）
 */
public record PermissionCatalogSnapshot(
    List<Permission> permissions,
    List<Role> roles,
    Map<Long, List<Long>> rolePermissionIds,
    String version,
    String generatedAt) {
}
