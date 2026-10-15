package io.openware.group.idaas.domain.auth.model;

/**
 * 集团 IDaaS 账号（领域模型）。
 *
 * <p>审计字段（created_by/at、updated_by/at）不在此承载：它们是记账信息，由持久化适配器填充，
 * 见 standards/10“聚合装配与持久化契约”。
 *
 * <p>{@code password} 是 BCrypt 哈希，属敏感字段：不得进入日志、不得作为 API 响应返回。
 *
 * @param id          聚合标识
 * @param username    登录名
 * @param password    密码哈希（BCrypt）
 * @param displayName 展示名
 * @param status      账号状态：ENABLED / 其它
 */
public record IdaasAccount(
    Long id,
    String username,
    String password,
    String displayName,
    String status) {

  /** 账号处于可用状态。 */
  public static final String STATUS_ENABLED = "ENABLED";

  /** 账号是否可用：登录用例以此为准，避免调用方各自比较字面量。 */
  public boolean isEnabled() {
    return STATUS_ENABLED.equals(status);
  }
}
