package io.openware.platform.customer.domain.member.repository;

import io.openware.platform.customer.domain.member.model.MemberNameToken;
import java.util.Collection;
import java.util.List;

/**
 * 客户姓名/客户号盲索引（{@code cst_member_name_token}）的仓储端口。
 *
 * <p><b>租户口径分两类，刻意不收敛</b>：
 * <ul>
 *   <li><b>请求路径</b>（{@link #findMemberIdsByAllTokens}）：调用方显式传 {@code tenantId}，
 *       适配器<b>不</b>忽略租户拦截器——除了这个显式谓词，拦截器还会再兜一层，
 *       缺上下文直接抛，不会静默跨租户；</li>
 *   <li><b>存量回填路径</b>（{@link #findMemberIdsMissingTokens}、{@link #countMembersMissingTokens}、
 *       {@link #listTenantIdsWithMembers}）：定时任务线程没有租户上下文，适配器必须显式忽略拦截器
 *       并<b>显式传 tenantId</b>；调用方在逐条写之前必须设好该客户的租户上下文
 *       （不设会被拦截器整批拒绝）。</li>
 * </ul>
 */
public interface MemberNameTokenRepository {

  /** 某客户的全部 token 行（按 id 升序）。 */
  List<MemberNameToken> findByMember(Long memberId);

  /**
   * 删掉某客户的全部 token 行，返回删掉的行数。
   *
   * <p>清理用例需要这个计数（写进审计详情），所以端口必须把行数回传——
   * 让调用方先查一遍再删会多一次往返，也会让"查到的行数"与"删掉的行数"在并发下不一致。
   */
  int deleteByMember(Long memberId);

  /**
   * 重建某客户的 token 集合：<b>先删后插</b>，天然幂等。
   *
   * <p>删除与插入在同一个方法内完成，因为"删了但没插上"会留下<b>半套索引</b>，
   * 而回填扫描的判据是"没有 token 行"——半套索引会被误判为已完成，此后按姓名再也搜不到这名客户。
   * 失败时由适配器补偿删除（退回零 token 行状态，交给下一轮回填），并把原异常抛出。
   */
  void replaceForMember(Long memberId, Collection<MemberNameToken> tokens, java.time.LocalDateTime occurredAt);

  /**
   * 关键词 token <b>全部命中</b>的候选 member_id（升序）。
   *
   * <p>命中判定用 {@code HAVING COUNT(DISTINCT token) = tokenCount}：只有"关键词切出的 n 个 token
   * 全都在该客户名下"才进候选集；走 {@code idx_cst_member_name_token_token} 索引做 IN 过滤，不做全表扫描。
   *
   * @param tenantId 租户（显式 + 拦截器双重过滤）
   * @param tokens   关键词 token（调用方保证非空）
   */
  List<Long> findMemberIdsByAllTokens(long tenantId, Collection<String> tokens);

  /** 存在客户档案的租户列表（跨租户、显式无上下文调用；回填任务用它逐租户处理）。 */
  List<Long> listTenantIdsWithMembers();

  /**
   * 指定租户下<b>没有 token 行</b>的客户 id（按 id 升序、游标分批）。
   *
   * <p>"没有 token 行" = 从未回填或上次回填补偿删除过 → 本方法天然幂等、可重入：
   * 已经建好索引的客户不会再被选中，失败中断后重新调用即可继续。
   */
  List<Long> findMemberIdsMissingTokens(long tenantId, long afterId, int limit);

  /** 指定租户下仍缺 token 的客户数（回填结束后报告 remaining）。 */
  long countMembersMissingTokens(long tenantId);
}
