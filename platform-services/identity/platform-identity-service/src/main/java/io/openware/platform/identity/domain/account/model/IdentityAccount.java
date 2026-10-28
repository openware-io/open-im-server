package io.openware.platform.identity.domain.account.model;

/**
 * 统一账号（领域模型）。
 *
 * <p>账号类型只表达平台侧属性：{@code PLATFORM_OPERATOR} / {@code USER}。员工/客户身份由
 * {@code tnt_membership.membership_type} 决定，不由全局账号类型决定。
 *
 * @param id          聚合标识
 * @param status      账号状态：ACTIVE / SUSPENDED / CLOSED
 * @param accountType 账号类型：PLATFORM_OPERATOR / USER
 */
public record IdentityAccount(Long id, String status, String accountType) {

  /** 账号处于可用状态。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 普通 SaaS 账号；员工/客户由租户成员身份区分。 */
  public static final String TYPE_USER = "USER";

  /** 平台运营账号；没有租户成员身份。 */
  public static final String TYPE_PLATFORM_OPERATOR = "PLATFORM_OPERATOR";

  /** 旧值仅用于历史数据归一化，不再写入。 */
  public static final String TYPE_CUSTOMER = TYPE_USER;

  /**
   * 注册新账号：USER 类型、初始为可用状态；此时尚未落库（{@code id} 为空）。
   */
  public static IdentityAccount register() {
    return new IdentityAccount(null, STATUS_ACTIVE, TYPE_USER);
  }

  /**
   * 按指定类型开通账号（内部端点供运营开通使用）：初始为可用状态，尚未落库。
   * 旧值 EMPLOYEE/CUSTOMER 归一化为 USER。
   */
  public static IdentityAccount ofType(String accountType) {
    return new IdentityAccount(null, STATUS_ACTIVE, normalizeAccountType(accountType));
  }

  /**
   * 修正账号类型；旧值 EMPLOYEE/CUSTOMER 归一化为 USER。
   *
   * @throws IllegalStateException 账号尚未落库
   */
  public IdentityAccount changeAccountType(String newAccountType) {
    if (id == null) {
      throw new IllegalStateException("cannot change the type of an account that is not persisted");
    }
    return new IdentityAccount(id, status, normalizeAccountType(newAccountType));
  }

  /** 统一账号类型：只允许 PLATFORM_OPERATOR / USER。 */
  public static String normalizeAccountType(String accountType) {
    if (accountType == null || accountType.isBlank()) {
      return TYPE_USER;
    }
    return switch (accountType) {
      case TYPE_USER, "EMPLOYEE", "CUSTOMER" -> TYPE_USER;
      case TYPE_PLATFORM_OPERATOR -> TYPE_PLATFORM_OPERATOR;
      default -> throw new IllegalArgumentException("unsupported account type");
    };
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
    return other != null && !other.isBlank()
        && !normalizeAccountType(other).equals(accountType);
  }
}
