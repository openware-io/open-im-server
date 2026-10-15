package io.openware.group.idaas.domain.client.model;

/**
 * 接入方 / OAuth 客户端（领域模型）。
 *
 * <p>审计字段不在此承载：由持久化适配器填充（standards/10“聚合装配与持久化契约”）。
 *
 * <p>{@code redirectUris} 是**以逗号分隔的原始串**，与既有存储形态一致：领域模型如实承载存储口径，
 * 而不是在这里虚构一个集合类型再让适配器来回转换。对外 DTO 的拆分由应用层负责。
 *
 * <p>{@code clientSecret} 属敏感字段：不得进入日志、不得作为 API 响应返回。
 *
 * @param id           聚合标识
 * @param name         接入方名称
 * @param clientId     客户端标识（唯一键，提供创建幂等）
 * @param clientSecret 客户端密钥
 * @param redirectUris 允许的回调地址，逗号分隔
 * @param status       状态：ENABLED / 其它
 */
public record IdaasClient(
    Long id,
    String name,
    String clientId,
    String clientSecret,
    String redirectUris,
    String status) {

  /** 接入方处于可用状态。 */
  public static final String STATUS_ENABLED = "ENABLED";

  /** 新建接入方：初始可用状态，尚未落库（{@code id} 为空）。 */
  public static IdaasClient register(String name, String clientId, String clientSecret,
      String redirectUris) {
    return new IdaasClient(null, name, clientId, clientSecret, redirectUris, STATUS_ENABLED);
  }
}
