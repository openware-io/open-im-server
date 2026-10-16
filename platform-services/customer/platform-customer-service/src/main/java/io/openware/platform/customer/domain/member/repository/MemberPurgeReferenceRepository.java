package io.openware.platform.customer.domain.member.repository;

/**
 * 客户档案删除前的<b>跨域引用体检</b>端口（一次查完、fail-closed）。
 *
 * <p>有订单/预约/券，或有储值账户余额、或有非零积分，就不允许删除——删了会留下孤儿引用。
 * 这里读的是 {@code ord_*} / {@code mkt_*} / {@code cst_*} 四类表的只读计数，
 * 按<b>已校验过租户的 customer_id</b> 精确计数（租户无关谓词），不参与任何写路径。
 *
 * <p>端口形状是<b>具名计数</b>而不是 {@code Map<String, Object>}：字符串键的体检结果
 * 让"少读了一个计数"和"键名拼错了"都只能在运行时暴露，而这里每一个计数都对应一句
 * 必须出现的中文拒绝理由（见 {@code MemberPurgeApplicationService#describeBlockers}）。
 */
public interface MemberPurgeReferenceRepository {

  /**
   * 体检一条客户档案的业务引用。
   *
   * <p>实现必须<b>一次查询</b>取回全部计数（不是六个往返），且查询失败要抛出——
   * 调用方把异常当成"不能删"处理（fail-closed），绝不因为读不到体检结果就放行删除。
   */
  PurgeBlockers blockersFor(Long memberId);

  /**
   * 引用体检计数。
   *
   * @param orders           订单数（{@code ord_order}）
   * @param reservations     预约数（{@code ord_reservation}）
   * @param wallets          有余额的储值账户数（{@code available_amount <> 0 OR frozen_amount <> 0}）
   * @param points           有余额的积分账户数（{@code available_points <> 0 OR frozen_points <> 0}）
   * @param couponsIssued    已发券数（{@code mkt_coupon_issuance}）
   * @param couponsRedeemed  券核销记录数（{@code mkt_coupon_redemption}）
   */
  record PurgeBlockers(long orders, long reservations, long wallets, long points,
                       long couponsIssued, long couponsRedeemed) {

    /** 是否一条引用都没有（可删）。 */
    public boolean isEmpty() {
      return orders == 0 && reservations == 0 && wallets == 0 && points == 0
          && couponsIssued == 0 && couponsRedeemed == 0;
    }
  }
}
