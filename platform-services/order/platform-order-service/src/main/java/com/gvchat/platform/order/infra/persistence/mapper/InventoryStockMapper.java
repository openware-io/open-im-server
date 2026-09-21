package com.gvchat.platform.order.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.order.infra.persistence.po.InventoryStockPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface InventoryStockMapper extends BaseMapper<InventoryStockPo> {
    @Select("SELECT * FROM ord_inventory_stock WHERE tenant_id=#{tenantId} AND store_id=#{storeId} AND material_id=#{materialId} FOR UPDATE")
    InventoryStockPo selectForUpdate(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId, @Param("materialId") Long materialId);

    /**
     * 原子扣减可用库存（防超卖的唯一权威入口）：
     * {@code on_hand_qty = on_hand_qty - n AND on_hand_qty - reserved_qty >= n} 由数据库在单条语句内
     * 以行锁判定，返回 0 行即「可用库存不足」，由调用方抛 {@code INVENTORY_INSUFFICIENT}。
     * 条件里带上下界，因此并发扣减不可能把库存扣成负数（不依赖应用层先读后判）。
     */
    @Update("UPDATE ord_inventory_stock SET on_hand_qty = on_hand_qty - #{quantity}, version = version + 1, updated_at = #{updatedAt} "
            + "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND material_id = #{materialId} "
            + "AND on_hand_qty - reserved_qty >= #{quantity}")
    int deductAvailable(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
                        @Param("materialId") Long materialId, @Param("quantity") BigDecimal quantity,
                        @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 入库/调整入库/作废回补：原子增加现有库存，并**在同一条语句内**落库新的移动加权平均成本。
     *
     * <p>{@code avgCost} 由调用方在 {@code selectForUpdate} 行锁内读出「入库前数量 + 旧平均成本」后定点算出，
     * 锁在本方法所在事务提交前一直持有，因此并发入库对同一 (租户, 门店, 物料) 严格串行，
     * 不会出现「两个批次都基于同一个旧平均成本重算」的丢失更新。
     *
     * @param avgCost          本次入库后的移动加权平均成本（最小货币单位/计量单位）
     * @param currencyCode     该平均成本的币种快照
     */
    @Update("UPDATE ord_inventory_stock SET on_hand_qty = on_hand_qty + #{quantity}, avg_cost = #{avgCost}, "
            + "currency_code = #{currencyCode}, version = version + 1, updated_at = #{updatedAt} "
            + "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND material_id = #{materialId}")
    int increaseOnHand(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
                       @Param("materialId") Long materialId, @Param("quantity") BigDecimal quantity,
                       @Param("avgCost") BigDecimal avgCost, @Param("currencyCode") String currencyCode,
                       @Param("updatedAt") LocalDateTime updatedAt);

    /** 按门店取库存余额（库存成本查询口径：结存数量 × 移动加权平均成本，按物料/币种）。 */
    @Select("SELECT * FROM ord_inventory_stock WHERE tenant_id = #{tenantId} AND store_id = #{storeId} ORDER BY material_id")
    List<InventoryStockPo> selectByStore(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId);
}
