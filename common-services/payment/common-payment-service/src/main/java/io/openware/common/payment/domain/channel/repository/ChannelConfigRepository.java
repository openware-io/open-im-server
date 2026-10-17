package io.openware.common.payment.domain.channel.repository;

import io.openware.common.payment.domain.channel.model.ChannelConfig;
import java.util.List;
import java.util.Optional;

/**
 * 支付渠道配置仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface ChannelConfigRepository {

  /**
   * 列出某租户下**生效中**的渠道配置（不做作用域解析，供调用方按用途解析）。
   *
   * <p>返回全部作用域的行而不是"已解析结果"：列表要看配置、收款要判开关，
   * 两种用途需要不同过滤，解析收在 {@code ChannelConfigResolver}。
   */
  List<Persisted<ChannelConfig>> listActiveByTenant(Long tenantId);

  /**
   * 按作用域读取单条渠道配置：租户 + 渠道 + 业态 +（门店级或租户级）。
   *
   * <p>{@code storeId} 为 {@code null} 表示租户级，实现必须按 {@code IS NULL} 匹配——
   * SQL 里 {@code = NULL} 永远不成立，用等值比较会把"租户级配置已存在"误判成"不存在"，
   * 于是每次写入都插入新行，而唯一键会让它直接失败。
   *
   * <p>不按 status 过滤：写路径需要看到停用行并复用它，否则会在同一作用域插入第二行。
   */
  Optional<Persisted<ChannelConfig>> findByScope(Long tenantId, Long storeId, String businessType, String channel);

  /** 按幂等键读取渠道配置（写入幂等判据）。 */
  Optional<Persisted<ChannelConfig>> findByIdempotencyKey(Long tenantId, String channel, String idempotencyKey);

  /** 新增配置（版本从 0 起）；审计列由适配器按 {@code occurredAt} 填充。 */
  Persisted<ChannelConfig> insert(ChannelConfig config, java.time.LocalDateTime occurredAt);

  /**
   * 乐观锁更新：仅当库中版本等于 {@code expectedVersion} 时写入并自增版本。
   *
   * <p>必须由单条条件 UPDATE（{@code ... SET version = version + 1 WHERE id = ? AND version = ?}）完成：
   * 先读出来比对再无条件写回，会让两个并发请求双双通过比对、后写者覆盖前者且双方都收到成功。
   *
   * @return 更新后的行；CAS 失败返回 empty
   */
  Optional<Persisted<ChannelConfig>> updateWithVersion(ChannelConfig config, int expectedVersion,
                                                       java.time.LocalDateTime occurredAt);

  /** 是否存在某租户/门店下**已开通且生效**的该渠道配置。 */
  boolean existsEnabled(Long tenantId, Long storeId, String channel);
}
