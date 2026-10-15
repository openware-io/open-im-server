package io.openware.platform.admin.domain.menu.repository;

/**
 * 租户支付能力授权只读端口。
 *
 * <p>只读端口归**调用方**（standards/10“跨上下文读取”）：支付方式的维护归租户经营上下文，
 * 本端口只提供"该租户是否被显式授权某项能力"这一读取能力。
 *
 * <p>命名用 {@code isGranted} 而不是暴露状态字段：调用方要的是"能不能用"这个结论，
 * 不是 {@code granted} 列的取值。
 */
public interface TenantPaymentGrantRepository {

  /** 该租户是否已被显式授权某项线上支付能力。 */
  boolean isGranted(Long tenantId, String method);
}
