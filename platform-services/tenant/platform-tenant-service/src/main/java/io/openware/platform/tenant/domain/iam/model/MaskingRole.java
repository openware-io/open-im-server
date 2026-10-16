package io.openware.platform.tenant.domain.iam.model;

/**
 * 遮蔽（脱敏）角色只读投影。
 *
 * <p>按 standards/10“领域模型与只读投影的边界”，它是**具名投影类型**而不是 PO 或裸 Map：
 * 该视图由一条跨表查询派生，字段集不等于任何单张表，因此不能复用 PO。
 *
 * @param roleId  角色标识
 * @param code    角色编码
 * @param name    角色名
 * @param piiView 是否可见个人敏感信息
 */
public record MaskingRole(
    Long roleId,
    String code,
    String name,
    boolean piiView) {
}
