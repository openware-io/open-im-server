package io.openware.platform.order.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.order.infra.persistence.po.InventoryWarehousePo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 仓库 / 库位 Mapper（{@code ord_inventory_warehouse}，V25）。
 *
 * <p>唯一键 {@code (tenant_id, business_type, store_id, code)}；默认仓 {@code code = DEFAULT}。
 */
@Mapper
public interface InventoryWarehouseMapper extends BaseMapper<InventoryWarehousePo> {

    @Select("SELECT * FROM ord_inventory_warehouse WHERE tenant_id = #{tenantId} AND id = #{warehouseId} LIMIT 1")
    InventoryWarehousePo selectById(@Param("tenantId") Long tenantId, @Param("warehouseId") Long warehouseId);

    /**
     * 取默认仓：{@code code = DEFAULT} 且启用；业态为空时只按门店定位（旧上下文兼容读）。
     * 按 id 升序取第一条，保证并发建仓后读取结果稳定。
     */
    @Select("""
            <script>
            SELECT * FROM ord_inventory_warehouse
            WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND code = 'DEFAULT' AND status = 'ACTIVE'
            <if test="businessType != null">AND business_type = #{businessType}</if>
            ORDER BY id
            LIMIT 1
            </script>
            """)
    InventoryWarehousePo selectDefault(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
                                       @Param("businessType") String businessType);

    @Select("""
            <script>
            SELECT * FROM ord_inventory_warehouse
            WHERE tenant_id = #{tenantId} AND store_id = #{storeId}
            <if test="businessType != null">AND business_type = #{businessType}</if>
            ORDER BY id
            </script>
            """)
    List<InventoryWarehousePo> selectByStore(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
                                             @Param("businessType") String businessType);
}
