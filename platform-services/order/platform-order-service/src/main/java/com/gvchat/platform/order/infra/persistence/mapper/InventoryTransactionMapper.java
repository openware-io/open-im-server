package com.gvchat.platform.order.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.order.infra.persistence.po.InventoryTransactionPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InventoryTransactionMapper extends BaseMapper<InventoryTransactionPo> {
    @Select("SELECT * FROM ord_inventory_transaction WHERE tenant_id=#{tenantId} AND idempotency_key=#{key} LIMIT 1")
    InventoryTransactionPo findByIdempotency(@Param("tenantId") Long tenantId, @Param("key") String key);

    /**
     * 取某来源明细最近一次「出库结转」的流水（回补 REVERSE 的成本还原口径：按当时出库用的单价回补，
     * 而不是按回补时点的平均成本），按 id 倒序取最新一条。
     */
    @Select("SELECT * FROM ord_inventory_transaction WHERE tenant_id=#{tenantId} AND store_id=#{storeId} "
            + "AND material_id=#{materialId} AND transaction_type IN ('CONSUME','ADJUST_OUT') "
            + "AND source_type=#{sourceType} AND source_id=#{sourceId} ORDER BY id DESC LIMIT 1")
    InventoryTransactionPo findLatestOutbound(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
                                              @Param("materialId") Long materialId,
                                              @Param("sourceType") String sourceType,
                                              @Param("sourceId") String sourceId);

    /** 按门店列流水（出库结转单价回填等只读诊断用）。 */
    @Select("SELECT * FROM ord_inventory_transaction WHERE tenant_id=#{tenantId} AND store_id=#{storeId} ORDER BY id DESC")
    List<InventoryTransactionPo> selectByStore(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId);
}
