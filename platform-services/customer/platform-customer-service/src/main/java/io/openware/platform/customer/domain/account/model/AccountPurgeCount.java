package io.openware.platform.customer.domain.account.model;

/**
 * 账户清理（purge）删掉的行数。
 *
 * <p>储值账户与积分账户的清理是同一件事的两次：删掉"零额账户"及其账本流水。
 * 两个仓储端口的清理方法都返回本类型，调用方（{@code MemberPurgeApplicationService}）
 * 才能把两个计数原样写进审计详情，而不用各自定义一套形状。
 *
 * @param accounts 删掉的账户行数
 * @param ledgers  删掉的账本流水行数
 */
public record AccountPurgeCount(int accounts, int ledgers) {

  /** 什么都没删（没有零额账户的常见情形）。 */
  public static AccountPurgeCount none() {
    return new AccountPurgeCount(0, 0);
  }
}
