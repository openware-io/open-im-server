package io.openware.platform.marketing.infra.persistence.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.platform.marketing.domain.coupon.model.DiscountType;
import io.openware.platform.marketing.domain.coupon.model.MktCoupon;
import io.openware.platform.marketing.domain.coupon.readmodel.PageResult;
import io.openware.platform.marketing.domain.coupon.repository.MktCouponRepository;
import io.openware.platform.marketing.infra.persistence.mapper.CouponMapper;
import io.openware.platform.marketing.infra.persistence.po.MktCouponPo;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link MktCouponRepository} 的持久化适配器。
 *
 * <p>分页插件的 {@code Page}/{@code IPage} 只在本层出现，端口返回领域侧的分页结果，
 * 因此分页插件类型不会渗进应用层与领域层。
 *
 * <p>审计字段由本层填充（mkt_coupon 的 created_at/updated_at 为 NOT NULL 无默认值）。
 */
@Repository
public class MktCouponRepositoryAdapter implements MktCouponRepository {

  private final CouponMapper mapper;

  public MktCouponRepositoryAdapter(CouponMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public PageResult<MktCoupon> list(long page, long pageSize, String status) {
    IPage<MktCouponPo> result = mapper.selectPage(new Page<>(page, pageSize),
        Wrappers.<MktCouponPo>lambdaQuery()
            .eq(status != null && !status.isBlank(), MktCouponPo::getStatus, status)
            .orderByDesc(MktCouponPo::getId));
    return new PageResult<>(result.getRecords().stream().map(this::toDomain).toList(),
        result.getTotal(), result.getCurrent(), result.getSize());
  }

  @Override
  public Optional<MktCoupon> findById(Long couponId) {
    return Optional.ofNullable(mapper.selectById(couponId)).map(this::toDomain);
  }

  @Override
  public MktCoupon save(MktCoupon coupon) {
    LocalDateTime now = LocalDateTime.now();
    MktCouponPo po = new MktCouponPo();
    po.setId(coupon.id());
    po.setTenantId(coupon.tenantId());
    po.setCampaignId(coupon.campaignId());
    po.setDiscountType(coupon.discountType() == null ? null : coupon.discountType().name());
    po.setDiscountValue(coupon.discountValue());
    po.setMinAmount(coupon.minAmount());
    po.setMaxDiscount(coupon.maxDiscount());
    po.setUsageLimit(coupon.usageLimit());
    po.setPerCustomerLimit(coupon.perCustomerLimit());
    po.setStatus(coupon.status());
    po.setVersion(coupon.version());
    if (po.getId() == null) {
      po.setCreatedAt(now);
      po.setUpdatedAt(now);
      mapper.insert(po);
    } else {
      int expectedVersion = coupon.version() == null ? 0 : coupon.version();
      int updated = mapper.update(null, Wrappers.<MktCouponPo>lambdaUpdate()
          .eq(MktCouponPo::getId, coupon.id())
          .eq(MktCouponPo::getVersion, expectedVersion)
          .set(MktCouponPo::getCampaignId, coupon.campaignId())
          .set(MktCouponPo::getDiscountType,
              coupon.discountType() == null ? null : coupon.discountType().name())
          .set(MktCouponPo::getDiscountValue, coupon.discountValue())
          .set(MktCouponPo::getMinAmount, coupon.minAmount())
          .set(MktCouponPo::getMaxDiscount, coupon.maxDiscount())
          .set(MktCouponPo::getUsageLimit, coupon.usageLimit())
          .set(MktCouponPo::getPerCustomerLimit, coupon.perCustomerLimit())
          .set(MktCouponPo::getStatus, coupon.status())
          .set(MktCouponPo::getUpdatedAt, now)
          .setSql("version = version + 1"));
      if (updated == 0) {
        throw new IllegalStateException("MKT_COUPON_VERSION_CONFLICT");
      }
      po.setVersion(expectedVersion + 1);
    }
    return toDomain(po);
  }

  private MktCoupon toDomain(MktCouponPo po) {
    return new MktCoupon(po.getId(), po.getTenantId(), po.getCampaignId(),
        po.getDiscountType() == null ? null : DiscountType.parse(po.getDiscountType()),
        po.getDiscountValue(), po.getMinAmount(), po.getMaxDiscount(), po.getUsageLimit(),
        po.getPerCustomerLimit(), po.getStatus(), po.getVersion());
  }
}
