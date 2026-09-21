package com.gvchat.platform.order.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.order.infra.persistence.po.OrderItemPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItemPo> {

    /**
     * 批量取多个订单「已生效（ACTIVE）的包厢计时费明细」：订单列表投影要把库内合计换成实时合计，
     * 必须一次把整页订单的房费明细金额查出来（逐单调用会变成 N+1）。
     *
     * <p>与写路径的单订单 {@code KtvSessionApplicationService#findRoomFeeItem(orderId)} 的区别：
     * 那个是「订单内唯一一行、更新它」的写路径查询；本方法是读路径批量汇总，
     * 行口径与账单一致（{@code BillApplicationService#roomFeeItemsMinor}：status=ACTIVE 的
     * ROOM_FEE 行金额求和，房费正常只有一行，历史脏数据多行时一并求和——订单合计的重算口径也是全行求和）。
     *
     * <p>返回明细行而不是 SQL 聚合值：金额列是 decimal，用 PO 回读后再在 Java 侧按与账单同一个
     * 「BigDecimal → 最小货币单位 long」收敛，避免 SQL 聚合结果取 Map 时列名大小写在
     * MySQL / 测试 H2 之间不一致（H2 会把未加引号的别名规整成大写）。
     *
     * @param orderIds 订单 id（调用方保证非空：空集合会拼出非法的 {@code IN ()}）
     * @return 命中订单的房费明细行，按 (order_id, id) 升序；没有任何房费明细的订单不会出现在结果里
     */
    @Select("""
            <script>
            SELECT * FROM ord_order_item
             WHERE item_type = 'ROOM_FEE' AND status = 'ACTIVE'
               AND order_id IN
               <foreach collection="orderIds" item="orderId" open="(" separator="," close=")">#{orderId}</foreach>
             ORDER BY order_id, id
            </script>
            """)
    List<OrderItemPo> selectActiveRoomFeeItemsByOrderIds(@Param("orderIds") List<Long> orderIds);

    /**
     * 批量取多个订单**全部**「已生效（ACTIVE）明细」：订单投影的实时合计要用两个数——
     * 全部明细合计（库内合计为 0 时的回退基数）与其中 ROOM_FEE 明细合计（差额法要扣掉的库内房费快照），
     * 一次查询同时拿齐，避免整页订单查两遍明细（更不能逐单查）。
     *
     * <p>行口径与账单/订单金额重算一致：只取 {@code status=ACTIVE}（待确认/已拒绝不计入应付）。
     * 返回明细行而不是 SQL 聚合值的原因见 {@link #selectActiveRoomFeeItemsByOrderIds(List)}。
     *
     * @param orderIds 订单 id（调用方保证非空：空集合会拼出非法的 {@code IN ()}）
     * @return 命中订单的全部 ACTIVE 明细行，按 (order_id, id) 升序；没有明细的订单不出现在结果里
     */
    @Select("""
            <script>
            SELECT * FROM ord_order_item
             WHERE status = 'ACTIVE'
               AND order_id IN
               <foreach collection="orderIds" item="orderId" open="(" separator="," close=")">#{orderId}</foreach>
             ORDER BY order_id, id
            </script>
            """)
    List<OrderItemPo> selectActiveItemsByOrderIds(@Param("orderIds") List<Long> orderIds);

    /**
     * 加项审批状态流转（**原子条件更新**，并发安全）：仅当明细仍是 {@code PENDING_APPROVAL} 时才改状态。
     *
     * <p>为什么不用「先查再 updateById」：客户加项后，多个运营（或同一人重复点击/两端同时操作）可能同时
     * 点「确认」或「拒绝」。先查后写会双写、甚至出现「先确认又拒绝」的竞态；条件更新把判定交给数据库，
     * 受影响行数为 0 即表示「已被别人处理或状态不符」，调用方据此回 409/幂等返回。
     *
     * @param tenantId 租户（租户拦截器之外再显式带一次，避免跨租户误更新）
     * @param orderId  订单（明细必须属于该订单）
     * @param itemId   明细
     * @param status   目标状态（ACTIVE / REJECTED）
     * @param updatedBy 操作人
     * @return 受影响行数（1 = 本次成功流转；0 = 已被并发处理或状态不符）
     */
    @Update("""
            UPDATE ord_order_item
               SET status = #{status}, updated_by = #{updatedBy}, updated_at = CURRENT_TIMESTAMP(3)
             WHERE tenant_id = #{tenantId} AND order_id = #{orderId} AND id = #{itemId}
               AND status = 'PENDING_APPROVAL'
            """)
    int markApproval(@Param("tenantId") Long tenantId, @Param("orderId") Long orderId,
                     @Param("itemId") Long itemId, @Param("status") String status,
                     @Param("updatedBy") Long updatedBy);
}
