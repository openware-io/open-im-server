package io.openware.group.idaas.domain.client.repository;

import io.openware.group.idaas.domain.client.model.IdaasClient;
import java.util.List;
import java.util.Optional;

/**
 * 接入方仓储端口。
 *
 * <p>以聚合为单位，方法表达业务意图；不暴露 MyBatis Wrapper、PO、Mapper 或分页类型。
 */
public interface IdaasClientRepository {

  /** 列出全部接入方（按标识升序，保持既有列表口径）。 */
  List<IdaasClient> listAll();

  /**
   * 按 client_id 读取（创建用例的幂等判据）。
   *
   * <p>真正防重复依赖 {@code client_id} 上的唯一键约束；本方法只提供"是否已存在"的查询视图。
   */
  Optional<IdaasClient> findByClientId(String clientId);

  /** 保存接入方：{@code id} 为空时新增，否则更新，返回落库后的聚合（含生成的标识）。 */
  IdaasClient save(IdaasClient client);
}
