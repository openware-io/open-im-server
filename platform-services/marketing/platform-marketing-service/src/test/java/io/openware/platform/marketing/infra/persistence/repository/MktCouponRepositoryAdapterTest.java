package io.openware.platform.marketing.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.marketing.domain.coupon.model.DiscountType;
import io.openware.platform.marketing.domain.coupon.model.MktCoupon;
import io.openware.platform.marketing.infra.persistence.mapper.CouponMapper;
import io.openware.platform.marketing.infra.persistence.po.MktCouponPo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Coupon CAS 的 SQL 组装：更新必须带 version 谓词并在 SQL 内自增；0 行即冲突。
 */
class MktCouponRepositoryAdapterTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MktCouponPo.class);
  }

  @SuppressWarnings("unchecked")
  @Test
  void updateUsesVersionPredicateAndIncrementsItInSql() {
    CouponMapper mapper = mock(CouponMapper.class);
    when(mapper.update(isNull(), any())).thenReturn(1);
    MktCouponRepositoryAdapter adapter = new MktCouponRepositoryAdapter(mapper);
    MktCoupon coupon = new MktCoupon(9L, 1000L, 20L, DiscountType.FIXED_AMOUNT, 500L, 0L, 0L,
        10, 1, MktCoupon.STATUS_ACTIVE, 4);

    adapter.save(coupon);

    ArgumentCaptor<Wrapper<MktCouponPo>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).update(isNull(), captor.capture());
    LambdaUpdateWrapper<MktCouponPo> wrapper = (LambdaUpdateWrapper<MktCouponPo>) captor.getValue();
    assertTrue(wrapper.getSqlSegment().contains("version"));
    assertTrue(wrapper.getSqlSet().contains("version = version + 1"));
  }

  @Test
  void zeroRowsMeansVersionConflict() {
    CouponMapper mapper = mock(CouponMapper.class);
    when(mapper.update(isNull(), any())).thenReturn(0);
    MktCouponRepositoryAdapter adapter = new MktCouponRepositoryAdapter(mapper);
    MktCoupon coupon = new MktCoupon(9L, 1000L, 20L, DiscountType.FIXED_AMOUNT, 500L, 0L, 0L,
        10, 1, MktCoupon.STATUS_ACTIVE, 4);

    IllegalStateException conflict = assertThrows(IllegalStateException.class, () -> adapter.save(coupon));

    assertEquals("MKT_COUPON_VERSION_CONFLICT", conflict.getMessage());
  }
}
