package io.openware.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.tenant.infra.persistence.po.BusinessEntitlementPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BusinessEntitlementMapper extends BaseMapper<BusinessEntitlementPo> {}
