package io.openware.platform.customer.domain.point.repository;

import io.openware.platform.customer.domain.point.model.PointRuleConfig;
import java.util.List;
import java.util.Optional;

/**
 * 积分规则配置仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface PointRuleConfigRepository {

  /**
   * 列出某租户下**生效中**的规则配置（供作用域解析在内存中挑选）。
   *
   * <p>返回全部层级而不是"已解析结果"：解析口径在
   * {@link io.openware.platform.customer.domain.point.service.PointRuleResolver}，
   * 查询不该替调用方决定用哪一条。
   */
  List<PointRuleConfig> listActiveByTenant(Long tenantId);

  /**
   * 按作用域读取配置：租户 + 业态 + 门店层级（{@code storeId == 0} 即租户级）。
   *
   * <p><b>不过滤 status</b>：写路径需要看到停用行并复用它，否则会在同一作用域插入第二行。
   * {@code businessType} 为空表示不限业态（按空串匹配）——业态参与定位，漏掉它会让不同业态的同名作用域
   * 互相覆盖。
   */
  Optional<PointRuleConfig> findByScope(Long tenantId, String businessType, Long storeId);

  /** 按幂等键读取配置（写入幂等判据）。 */
  Optional<PointRuleConfig> findByIdempotencyKey(Long tenantId, String idempotencyKey);

  /** 新增配置（版本从 0 起），返回落库后的聚合（含回填主键）。 */
  PointRuleConfig insert(PointRuleConfig config);

  /**
   * 乐观锁更新：仅当库中版本等于 {@code expectedVersion} 时写入并自增版本，返回是否成功。
   *
   * <p>必须由单条条件 UPDATE（{@code ... SET version = version + 1 WHERE id = ? AND version = ?}）完成：
   * 先读出来比对再无条件写回，会让两个并发写者双双通过比对，后写者覆盖前者且双方都收到成功——
   * 积分倍率被静默改错会直接算错用户积分。
   */
  boolean updateWithVersion(PointRuleConfig config, int expectedVersion);
}
