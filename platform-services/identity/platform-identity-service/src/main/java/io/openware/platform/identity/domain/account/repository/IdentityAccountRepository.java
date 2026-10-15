package io.openware.platform.identity.domain.account.repository;

import io.openware.platform.identity.domain.account.model.IdentityAccount;
import java.util.List;
import java.util.Optional;

/**
 * 统一账号仓储端口。
 *
 * <p>以聚合为单位，方法表达业务意图；不暴露 MyBatis Wrapper、PO、Mapper 或分页类型
 * （见 standards/10“端口命名与契约”）。
 *
 * <p>审计字段（创建人／时间、更新人／时间）由适配器在 {@link #save} 内填充，调用方不感知。
 */
public interface IdentityAccountRepository {

  /** 按聚合标识读取账号。 */
  Optional<IdentityAccount> findById(Long id);

  /**
   * 按账号类型列出账号。
   *
   * <p>用于批量场景（例如统一账号视图需要一次取回某一类账号）；结果可能较大，调用方须自行分页或限定范围。
   */
  List<IdentityAccount> findByAccountType(String accountType);

  /** 统计指定账号类型的数量。 */
  long countByAccountType(String accountType);

  /** 保存账号：{@code id} 为空时新增，否则更新，返回落库后的聚合（含生成的标识）。 */
  IdentityAccount save(IdentityAccount account);
}
