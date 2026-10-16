package io.openware.platform.customer.api.dto;

import io.openware.platform.customer.domain.point.model.PointAccount;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository.LedgerPage;
import java.util.ArrayList;
import java.util.List;

/**
 * 会员积分视图：积分账户 + 流水分页（{@code GET /business/members/{id}/points}、{@code GET /me/points}）。
 *
 * <p><b>数量口径</b>：账户的 {@code availablePoints} / {@code frozenPoints} 与流水的
 * {@code points} / {@code balanceAfter} 都是积分<b>个数</b>（1 积分 = 1 个，1:1 不换算）。
 * 积分不是货币也不是储值代币：本响应<b>不含币种</b>（流水用 {@link PointLedgerRow} 剥离
 * {@code currencyCode} 快照），也<b>不含 tokenAmount</b>（代币换算只适用于钱包，见
 * {@code WalletTokenDisplayService}）。
 *
 * <p><b>外形与迁移前一致</b>：此前 {@code ledger} 是 MyBatis-Plus 的 {@code Page}，
 * 序列化键是 {@code records / total / size / current / pages / orders / optimizeCountSql /
 * searchCount / maxLimit / countId}。这里保留前五个（调用方读的就是它们），
 * 末五个是 MyBatis-Plus 的内部开关：{@code optimizeCountSql}/{@code searchCount} 恒为
 * 字面量 {@code true}，{@code orders}/{@code maxLimit}/{@code countId} 恒为 {@code []}/{@code null}，
 * 不承载任何分页信息。{@code records} 由端口返回的领域条目装配，不再是持久化对象。
 *
 * <p>由 {@link #from(PointAccount, LedgerPage, long, long)} 装配，因此领域条目不可能被直接序列化出去。
 */
public record MemberPointsView(
    PointAccountView account,
    List<PointLedgerRow> records,
    long total,
    long size,
    long current,
    long pages) {

  /** 由领域账户与端口返回的一页流水装配；{@code page}/{@code pageSize} 是请求参数（响应照原样回显）。 */
  public static MemberPointsView from(PointAccount account, LedgerPage page, long pageNo, long pageSize) {
    List<PointLedgerRow> rows = new ArrayList<>();
    if (page != null && page.entries() != null) {
      for (var entry : page.entries()) {
        rows.add(PointLedgerRow.from(entry));
      }
    }
    long total = page == null ? 0L : page.total();
    long safeSize = pageSize < 1 ? 1 : pageSize;
    long pages = total == 0 ? 0 : (total + safeSize - 1) / safeSize;
    return new MemberPointsView(PointAccountView.from(account), rows, total, safeSize, pageNo, pages);
  }
}
