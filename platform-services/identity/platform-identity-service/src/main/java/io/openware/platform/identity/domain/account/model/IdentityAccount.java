package io.openware.platform.identity.domain.account.model;

/**
 * 统一账号（领域模型）。
 *
 * <p>账号类型区分员工／客户／平台运营（统一账号模型：共表按类型区分）。审计字段（创建人／时间、
 * 更新人／时间）不在此承载：它们是记账信息，由持久化适配器或统一拦截器填充，见 standards/10
 * “聚合装配与持久化契约”。
 *
 * @param id          聚合标识
 * @param status      账号状态
 * @param accountType 账号类型：EMPLOYEE / CUSTOMER / PLATFORM_OPERATOR
 */
public record IdentityAccount(Long id, String status, String accountType) {

  /** 新建账号：未落库（{@code id} 为空），状态由调用方按业务用例给定。 */
  public static IdentityAccount create(String status, String accountType) {
    return new IdentityAccount(null, status, accountType);
  }
}
