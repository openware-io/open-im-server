package com.gvchat.platform.customer.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.customer.infra.persistence.po.CstWalletLedgerPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WalletLedgerMapper extends BaseMapper<CstWalletLedgerPo> {
}
