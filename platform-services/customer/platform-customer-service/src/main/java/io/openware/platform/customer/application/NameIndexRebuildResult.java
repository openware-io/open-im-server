package io.openware.platform.customer.application;

/**
 * 姓名盲索引回填结果（{@code POST /business/members/name-index/rebuild} 的响应）。
 *
 * @param tenantId  处理的租户
 * @param scanned   本批扫描到的「缺 token 客户」条数（不含已建好索引的客户）
 * @param rebuilt   成功重建 token 的条数
 * @param failed    失败的条数（单条失败不中断整批，下一轮重试）
 * @param remaining 处理完仍缺 token 的客户数（0 = 该租户已回填完毕）
 */
public record NameIndexRebuildResult(long tenantId, int scanned, int rebuilt, int failed, long remaining) {
}
