package com.gvchat.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.tenant.infra.persistence.po.WalletAccountCurrencyPo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会员储值账户币种联动用 Mapper（见 {@link WalletAccountCurrencyPo}）。
 * 租户边界由 TenantLineInnerInterceptor 自动附加 `tenant_id`。
 */
@Mapper
public interface WalletAccountCurrencyMapper extends BaseMapper<WalletAccountCurrencyPo> {
}
