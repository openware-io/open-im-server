package io.openware.platform.admin.domain.menu.readmodel;

/**
 * 后台菜单行（只读投影）。
 *
 * <p>按 standards/10“领域模型与只读投影的边界”，菜单用于组装菜单树与按权限剪枝，属**只读聚合查询**：
 * 列出在规范里正是投影场景，且菜单行本身从不参与状态变更（剪枝作用在已组装的响应节点上）。因此用**具名
 * 投影**承载，而不是为配置数据虚构一个带有生命周期的聚合根。
 *
 * <p>不复用持久化对象（{@code AdminMenuPo}），也不返回裸 {@code Map}：规范对投影的要求是"具名且不复用 PO"。
 *
 * <p>{@code requiredPermission} / {@code requiredGrant} 只是**查询出来的声明**，其裁决权属于 tenant-IAM；
 * 投影不承载授权判定，也不应把授权规则下沉到菜单概念里。
 *
 * @param id                 菜单标识
 * @param parentId           父菜单标识，{@code 0} 表示顶层
 * @param code               菜单编码
 * @param name               菜单名
 * @param path               前端路由
 * @param icon               图标
 * @param scopeLevel         作用域层级：PLATFORM / TENANT / STORE
 * @param domainCode         业务域编码
 * @param requiredPermission 访问所需声明式权限（可空）
 * @param requiredGrant      访问所需显式授权能力（可空）
 * @param businessType       适用业态，逗号分隔白名单（可空）
 * @param i18nKey            国际化键
 */
public record AdminMenuRow(
    Long id,
    Long parentId,
    String code,
    String name,
    String path,
    String icon,
    String scopeLevel,
    String domainCode,
    String requiredPermission,
    String requiredGrant,
    String businessType,
    String i18nKey) {
}
