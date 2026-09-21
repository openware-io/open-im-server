package com.gvchat.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.tenant.infra.persistence.po.StorePo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface StoreMapper extends BaseMapper<StorePo> {

    /**
     * 跨租户鉴别：忽略租户拦截，仅按主键读出 {@code tenant_id}，用于区分「门店不存在」（404）与「跨租户」（403）。
     *
     * <p>不能用 {@code selectById} 代替：租户拦截器会把别的租户的门店过滤成 {@code null}，
     * 于是「越权访问」被静默降级成「不存在」，调用方无法从错误码上区分两者。
     * 与 {@code OrderMapper.selectTenantIdById} 同款写法。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT tenant_id FROM tnt_store WHERE id = #{id}")
    Long selectTenantIdById(@Param("id") Long id);
}
