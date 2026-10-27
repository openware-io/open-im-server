package io.openware.platform.order.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.order.infra.persistence.po.InventoryStockPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存余额 Mapper。
 *
 * <p><b>作用域</b>：余额作用域是 {@code tenant_id + business_type + store_id + warehouse_id +
 * material_id}（V25）。两条原子更新与行锁读都同时带上门店/仓库/物料定位与业态、组织快照过滤：
 * 业态/组织用「相等或旧行为 NULL」的口径是为了让 V24 之前/回填不完整的历史行仍可被原子更新并**顺带自愈**
 * （UPDATE 里 {@code COALESCE} 回填快照），同时另一个业态的行永远不会被匹配到。
 *
 * <p><b>为什么 SQL 写在注解里</b>：防超卖与移动加权平均的正确性由这两条单语句 UPDATE 的结构保证，
 * {@code InventoryStockRepositoryAdapterTest} 会反射读取注解并钉住关键片段，任何把判定搬回应用层的
 * 改写都会失败。
 */
@Mapper
public interface InventoryStockMapper extends BaseMapper<InventoryStockPo> {

    /** 加行锁读取库存行（事务内持有至提交）。 */
    @Select("""
            <script>
            SELECT * FROM ord_inventory_stock
            WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND warehouse_id = #{warehouseId}
              AND material_id = #{materialId}
            <if test="businessType != null">AND (business_type = #{businessType} OR business_type IS NULL)</if>
            <if test="organizationId != null">AND (organization_id = #{organizationId} OR organization_id IS NULL)</if>
            FOR UPDATE
            </script>
            """)
    InventoryStockPo selectForUpdate(@Param("tenantId") Long tenantId,
                                     @Param("organizationId") Long organizationId,
                                     @Param("businessType") String businessType,
                                     @Param("storeId") Long storeId,
                                     @Param("warehouseId") Long warehouseId,
                                     @Param("materialId") Long materialId);

    /**
     * 原子扣减可用库存（防超卖的唯一权威入口）：
     * {@code on_hand_qty = on_hand_qty - n AND on_hand_qty - reserved_qty >= n} 由数据库在单条语句内
     * 以行锁判定，返回 0 行即「可用库存不足」，由调用方抛 {@code INVENTORY_INSUFFICIENT}。
     * 条件里带上下界，因此并发扣减不可能把库存扣成负数（不依赖应用层先读后判）。
     */
    @Update("""
            <script>
            UPDATE ord_inventory_stock
            SET on_hand_qty = on_hand_qty - #{quantity},
                revision = revision + 1,
                updated_at = #{updatedAt},
                organization_id = COALESCE(organization_id, #{organizationId}),
                business_type = COALESCE(business_type, #{businessType})
            WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND warehouse_id = #{warehouseId}
              AND material_id = #{materialId}
            <if test="businessType != null">AND (business_type = #{businessType} OR business_type IS NULL)</if>
            <if test="organizationId != null">AND (organization_id = #{organizationId} OR organization_id IS NULL)</if>
              AND on_hand_qty - reserved_qty >= #{quantity}
            </script>
            """)
    int deductAvailable(@Param("tenantId") Long tenantId,
                        @Param("organizationId") Long organizationId,
                        @Param("businessType") String businessType,
                        @Param("storeId") Long storeId,
                        @Param("warehouseId") Long warehouseId,
                        @Param("materialId") Long materialId,
                        @Param("quantity") BigDecimal quantity,
                        @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 入库/调整入库/调拨入库/作废回补：原子增加现有库存，并**在同一条语句内**落库新的移动加权平均成本。
     *
     * <p>{@code avgCost} 由调用方在 {@code selectForUpdate} 行锁内读出「入库前数量 + 旧平均成本」后定点算出，
     * 锁在本方法所在事务提交前一直持有，因此并发入库对同一作用域严格串行，
     * 不会出现「两个批次都基于同一个旧平均成本重算」的丢失更新。
     *
     * @param avgCost      本次入库后的移动加权平均成本（最小货币单位/计量单位）
     * @param currencyCode 该平均成本的币种快照
     */
    @Update("""
            <script>
            UPDATE ord_inventory_stock
            SET on_hand_qty = on_hand_qty + #{quantity},
                avg_cost = #{avgCost},
                currency_code = #{currencyCode},
                revision = revision + 1,
                updated_at = #{updatedAt},
                organization_id = COALESCE(organization_id, #{organizationId}),
                business_type = COALESCE(business_type, #{businessType})
            WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND warehouse_id = #{warehouseId}
              AND material_id = #{materialId}
            <if test="businessType != null">AND (business_type = #{businessType} OR business_type IS NULL)</if>
            <if test="organizationId != null">AND (organization_id = #{organizationId} OR organization_id IS NULL)</if>
            </script>
            """)
    int increaseOnHand(@Param("tenantId") Long tenantId,
                       @Param("organizationId") Long organizationId,
                       @Param("businessType") String businessType,
                       @Param("storeId") Long storeId,
                       @Param("warehouseId") Long warehouseId,
                       @Param("materialId") Long materialId,
                       @Param("quantity") BigDecimal quantity,
                       @Param("avgCost") BigDecimal avgCost,
                       @Param("currencyCode") String currencyCode,
                       @Param("updatedAt") LocalDateTime updatedAt);

    /** 按门店取库存余额（库存成本查询口径：结存数量 × 移动加权平均成本，按仓库/物料/币种）。 */
    @Select("""
            <script>
            SELECT * FROM ord_inventory_stock
            WHERE tenant_id = #{tenantId} AND store_id = #{storeId}
            <if test="businessType != null">AND (business_type = #{businessType} OR business_type IS NULL)</if>
            <if test="warehouseId != null">AND warehouse_id = #{warehouseId}</if>
            ORDER BY warehouse_id, material_id
            </script>
            """)
    List<InventoryStockPo> selectByStore(@Param("tenantId") Long tenantId,
                                         @Param("businessType") String businessType,
                                         @Param("storeId") Long storeId,
                                         @Param("warehouseId") Long warehouseId);
}
