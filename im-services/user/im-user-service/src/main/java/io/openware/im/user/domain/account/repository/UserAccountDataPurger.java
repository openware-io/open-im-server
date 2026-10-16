package io.openware.im.user.domain.account.repository;

/** 用户账号数据清理端口：硬删指定用户在本服务拥有的全部业务数据（不含账号主记录）。 */
public interface UserAccountDataPurger {
  void purge(Long userId);
}
