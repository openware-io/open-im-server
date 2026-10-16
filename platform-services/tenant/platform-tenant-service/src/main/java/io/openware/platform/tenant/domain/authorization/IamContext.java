package io.openware.platform.tenant.domain.authorization;

/**
 * 账号可访问的经营上下文事实（租户/组织/门店各层的一条记录）。
 *
 * <p>与 {@link AccountContext} 的区别：这里是**未装配的角色为空的事实行**，
 * 角色列表由应用层按作用域聚合后填入，因此不复用同一个类型——否则调用方无法区分
 * "这条上下文确实没有角色"与"角色还没装配"。
 *
 * @param tenantId         租户
 * @param tenantName       租户名
 * @param organizationId   组织
 * @param organizationName 组织名
 * @param storeId          门店
 * @param storeName        门店名
 * @param scopeType        作用域类型
 * @param businessType     业态
 * @param timezone         时区
 */
public record IamContext(
    Long tenantId,
    String tenantName,
    Long organizationId,
    String organizationName,
    Long storeId,
    String storeName,
    String scopeType,
    String businessType,
    String timezone) {
}
