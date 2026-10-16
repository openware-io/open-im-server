package io.openware.platform.customer.infra.persistence.repository;

import io.openware.platform.customer.domain.member.repository.MemberPurgeReferenceRepository;
import io.openware.platform.customer.infra.persistence.mapper.MemberMapper;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * {@link MemberPurgeReferenceRepository} 的持久化适配器。
 *
 * <p>体检是<b>一次查询</b>取回全部计数（{@code MemberMapper#selectPurgeBlockers} 用子查询拼在一行里），
 * 查询失败直接抛出——调用方把异常当成"不能删"处理（fail-closed），
 * 绝不因为读不到体检结果就放行删除。
 */
@Repository
public class MemberPurgeReferenceRepositoryAdapter implements MemberPurgeReferenceRepository {

  private final MemberMapper memberMapper;

  public MemberPurgeReferenceRepositoryAdapter(MemberMapper memberMapper) {
    this.memberMapper = memberMapper;
  }

  @Override
  public PurgeBlockers blockersFor(Long memberId) {
    Map<String, Object> row = memberMapper.selectPurgeBlockers(memberId);
    if (row == null || row.isEmpty()) {
      // 理论上不会发生（六个子查询恒有值）；返回全 0 会让调用方误判"可删"，
      // 因此这里按"读不到 = 有引用"的方向保守失败。
      throw new IllegalStateException("客户引用体检结果缺失: memberId=" + memberId);
    }
    return new PurgeBlockers(
        count(row, "orders_count"),
        count(row, "reservations_count"),
        count(row, "wallets_count"),
        count(row, "points_count"),
        count(row, "coupons_issued_count"),
        count(row, "coupons_redeemed_count"));
  }

  /** 计数缺失按 0 处理；跨域查询的装箱类型（Integer/Long/BigDecimal）一律按 Number 取值。 */
  private static long count(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value instanceof Number number ? number.longValue() : 0L;
  }
}
