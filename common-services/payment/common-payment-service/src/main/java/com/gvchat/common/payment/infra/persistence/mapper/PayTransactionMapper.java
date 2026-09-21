package com.gvchat.common.payment.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.common.payment.infra.persistence.po.PayTransactionPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PayTransactionMapper extends BaseMapper<PayTransactionPo> {
}
