package io.openware.platform.marketing.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.marketing.infra.persistence.po.MktCouponRedemptionPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CouponRedemptionMapper extends BaseMapper<MktCouponRedemptionPo> {
}
