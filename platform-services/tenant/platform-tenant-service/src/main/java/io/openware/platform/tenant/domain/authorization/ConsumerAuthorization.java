package io.openware.platform.tenant.domain.authorization;

/**
 * 消费者应用（consumer）的授权事实投影。
 *
 * <p>按 standards/10“领域模型与只读投影的边界”，它是**具名投影类型**：由一条跨表查询派生，
 * 字段集不等于任何单张表，因此不复用 PO/行对象。
 *
 * @param appId                应用标识
 * @param tenantId             租户
 * @param tenantName           租户名
 * @param organizationId       组织
 * @param organizationName     组织名
 * @param storeId              门店
 * @param storeName            门店名
 * @param permissionsJson      权限码 JSON 数组（由调用方解析）
 * @param authorizationVersion 授权版本（缓存失效依据）
 */
public record ConsumerAuthorization(
    String appId,
    Long tenantId,
    String tenantName,
    Long organizationId,
    String organizationName,
    Long storeId,
    String storeName,
    String permissionsJson,
    Integer authorizationVersion) {
}
