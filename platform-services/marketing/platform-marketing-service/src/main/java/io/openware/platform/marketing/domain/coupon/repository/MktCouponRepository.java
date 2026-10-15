package io.openware.platform.marketing.domain.coupon.repository;

import io.openware.platform.marketing.domain.coupon.model.MktCoupon;
import io.openware.platform.marketing.domain.coupon.readmodel.PageResult;
import java.util.Optional;

/**
 * 优惠券仓储端口。
 *
 * <p>方法表达业务意图；不暴露 MyBatis Wrapper、PO、Mapper 或 {@code IPage}/{@code Page}。
 */
public interface MktCouponRepository {

  /**
   * 分页列出优惠券（按标识倒序），可按状态过滤。
   *
   * <p>状态过滤与排序都是查询语义，收在端口里，避免调用方在应用层拼查询条件。
   */
  PageResult<MktCoupon> list(long page, long pageSize, String status);

  /** 按标识读取优惠券。 */
  Optional<MktCoupon> findById(Long couponId);

  /** 保存优惠券：{@code id} 为空时新增，否则更新，返回落库后的聚合（含生成的标识）。 */
  MktCoupon save(MktCoupon coupon);
}
