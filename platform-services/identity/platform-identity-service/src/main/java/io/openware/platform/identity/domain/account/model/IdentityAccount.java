package io.openware.platform.identity.domain.account.model;

/**
 * 统一账号（领域模型）。
 *
 * <p>账号类型区分员工／客户／平台运营（统一账号模型：共表按类型区分）。审计字段（创建人／时间、
 * 更新人／时间）不在此承载：它们是记账信息，由持久化适配器填充，见 standards/10“聚合装配与持久化契约”。
 *
 * <p>状态迁移经具名方法完成，不提供整体设值方法：{@link #changeAccountType(String)} 是唯一允许修改
 * {@code accountType} 的入口，且只对已落库账号生效（未落库的账号不应存在改类型语义）。
 *
 * @param id          聚合标识
 * @param status      账号状态：ACTIVE / SUSPENDED / CLOSED
 * @param accountType 账号类型：EMPLOYEE / CUSTOMER / PLATFORM_OPERATOR
 */
public record IdentityAccount(Long id, String status, String accountType) {

  /** 账号处于可用状态。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 消费者账号类型：手机号／邮箱注册进入的类型。 */
  public static final String TYPE_CUSTOMER = "CUSTOMER";

  /**
   * 注册新账号：消费者类型、初始为可用状态；此时尚未落库（{@code id} 为空）。
   *
   * <p>状态与类型由本方法固定，调用方不得以参数覆盖——这是注册用例的不变量，不是可配置项。
   */
  public static IdentityAccount register() {
    return new IdentityAccount(null, STATUS_ACTIVE, TYPE_CUSTOMER);
  }

  /**
   * 修正账号类型（例如运营人员把已有账号标记为 EMPLOYEE）。
   *
   * @throws IllegalStateException 账号尚未落库，或类型为空
   */
  public IdentityAccount changeAccountType(String newAccountType) {
    if (id == null) {
      throw new IllegalStateException("cannot change the type of an account that is not persisted");
    }
    if (newAccountType == null || newAccountType.isBlank()) {
      throw new IllegalStateException("account type must not be blank");
    }
    return new IdentityAccount(id, status, newAccountType);
  }

  /** 校验账号可用：非 ACTIVE 即拒绝，供登录／绑定类用例复用同一条不变量。 */
  public IdentityAccount ensureActive() {
    if (!STATUS_ACTIVE.equals(status)) {
      throw new IllegalStateException("Identity account is not active");
    }
    return this;
  }

  /** 账号类型是否与给定值不同（避免无意义的写回）。 */
  public boolean hasDifferentAccountType(String other) {
    return other != null && !other.isBlank() && !other.equals(accountType);
  }
}
