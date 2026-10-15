package io.openware.group.idaas.domain.auth.repository;

import io.openware.group.idaas.domain.auth.model.IdaasAccount;
import java.util.Optional;

/**
 * 集团 IDaaS 账号仓储端口。
 *
 * <p>以聚合为单位，方法表达业务意图；不暴露 MyBatis Wrapper、PO、Mapper 或分页类型
 * （见 standards/10“端口命名与契约”）。
 */
public interface IdaasAccountRepository {

  /** 按登录名读取账号（登录流程的权威入口）。 */
  Optional<IdaasAccount> findByUsername(String username);
}
