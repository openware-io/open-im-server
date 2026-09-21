package com.gvchat.platform.admin.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.admin.infra.persistence.po.TenantPaymentMethodPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantPaymentMethodMapper extends BaseMapper<TenantPaymentMethodPo> {
}
