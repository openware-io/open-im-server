package io.openware.platform.marketing.domain.coupon.repository;

import io.openware.platform.marketing.domain.coupon.model.MktCouponIssuance;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 优惠券发放仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型。
 */
public interface MktCouponIssuanceRepository {

  /** 按券 + 客户读取唯一发放记录（唯一键 uk_mkt_coupon_issuance_customer 保证最多一条）。 */
  Optional<MktCouponIssuance> findUnique(Long couponId, Long customerId);

  /** 按幂等键读取发放记录（唯一键 uk_mkt_coupon_issuance_idempotency 保证最多一条）。 */
  Optional<MktCouponIssuance> findByIdempotencyKey(String idempotencyKey);

  /**
   * 新增发放记录（{@code id} 必须为空）。
   *
   * <p>已落库的发放只能经 {@link #markRedeemed} 迁移状态；实现必须拒绝带 {@code id} 的调用，
   * 否则会绕过状态机与版本 CAS。
   */
  MktCouponIssuance save(MktCouponIssuance issuance);

  /**
   * 将发放置为已核销，**乐观锁 + 状态机条件更新**。
   *
   * <p><b>原子性契约</b>：必须由**单条** UPDATE 完成，WHERE 同时限定
   * {@code id}、{@code status = ISSUED}、{@code version = expectedVersion}，
   * SET 状态为已核销、写入核销时间与订单、版本自增。
   *
   * <p>返回 {@code true} 表示本次调用赢得核销。若改为"先读后判再写"，两个并发请求会同时通过状态与版本检查
   * 而各自写入一条核销记录（重复核销），因此这条契约不得退化为读改写。
   *
   * @param expectedVersion 调用方观察到的版本号；为空时用记录当前版本
   */
  boolean markRedeemed(Long issuanceId, Integer expectedVersion, Long orderId, LocalDateTime redeemedAt);
}
