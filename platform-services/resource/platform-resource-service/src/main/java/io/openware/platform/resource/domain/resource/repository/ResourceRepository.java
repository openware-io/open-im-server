package io.openware.platform.resource.domain.resource.repository;

import io.openware.platform.resource.domain.resource.model.Resource;
import java.util.List;
import java.util.Optional;

/**
 * 资源仓储端口。
 *
 * <p>方法表达业务意图；不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 *
 * <p>审计字段由适配器在 {@link #save} 内填充（res_resource 的 created_at/updated_at 为 NOT NULL 无默认值）。
 */
public interface ResourceRepository {

  /**
   * 按类型列出**启用中**的资源，可按门店限定。
   *
   * <p>「启用中」的口径是建表注释里的 {@code ENABLED}（非 ACTIVE），由适配器落实为条件，
   * 调用方不需要知道取值。
   *
   * @param storeId 门店；{@code null} 表示不限定门店（沿用历史行为）
   */
  List<Resource> listEnabledByType(String resourceType, Long storeId);

  /** 按标识读取资源。 */
  Optional<Resource> findById(Long resourceId);

  /** 保存资源：{@code id} 为空时新增，否则更新，返回落库后的聚合。 */
  Resource save(Resource resource);
}
