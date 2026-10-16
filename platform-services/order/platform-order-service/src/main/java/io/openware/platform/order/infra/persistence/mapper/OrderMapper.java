package io.openware.platform.order.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderMapper extends BaseMapper<OrderPo> {

    /** 跨租户鉴别：忽略租户拦截，仅按主键读取 tenant_id（区分「不存在」vs「跨租户」）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT tenant_id FROM ord_order WHERE id = #{id}")
    Long selectTenantIdById(@Param("id") Long id);

    /**
     * 结算落库的乐观锁提交：仅当库中 {@code version} 等于 {@code expectedVersion} 时写入。
     *
     * <p><b>为什么必须是一条带 version 条件的 UPDATE</b>：`ord_order` 有 `version` 列（DDL 注释即
     * 「乐观锁版本号」），但 `OrderPo` 上没有 `@Version` 注解、工程里也没有乐观锁拦截器
     * （{@code OptimisticLockerInnerInterceptor}），所以 `updateById` **不会**附加版本条件。
     * 只靠应用层"读出来比对 expectedVersion"不是并发保护：两个并发结算都读到 version 5、
     * 都通过比对、都写 version 6、都返回成功——后写者覆盖前者的金额快照，而结算正是固化应付金额的地方。
     *
     * <p>列集合覆盖结算真正会改动的全部字段：{@code recalculate} 产出的五个金额列 + 状态 + 版本 + 更新时间。
     * 漏掉任一金额列都会让该列静默保留旧值——把金额列写全与加版本条件同等重要。
     *
     * <p>与 {@code KtvSessionMapper.updateWithVersion} 同形态：受影响 0 行由调用方转成明确冲突，不静默成功。
     *
     * @return 受影响行数；0 表示期间已被他人写入
     */
    /**
     * 订单金额与状态的乐观锁提交：仅当库中 {@code version} 等于 {@code expectedVersion} 时写入。
     *
     * <p><b>为什么必须是一条带 version 条件的 UPDATE</b>：{@code ord_order} 有 {@code version} 列（DDL 注释即
     * 「乐观锁版本号」），但 {@code OrderPo} 上没有 {@code @Version} 注解、工程里也没有乐观锁拦截器
     * （{@code OptimisticLockerInnerInterceptor}），所以 {@code updateById} **不会**附加版本条件。
     * 只靠应用层"读出来比对"不是并发保护：两个并发写者都读到 version 5、都通过比对、都写 version 6、
     * 都返回成功——后写者覆盖前者，而这里写的是 {@code recalculate} 算出的应付金额。
     *
     * <p>列集合覆盖订单金额重算与状态转换真正会改动的全部字段：五个金额列 + 状态 + 版本 + 更新时间。
     * 漏掉任一金额列都会让该列静默保留旧值——把金额列写全与加版本条件同等重要。
     *
     * <p>调用方（结算、KTV 开台/结台/定时刷新）都走这一条，避免同一张表出现几套各自演化的写入列集合。
     *
     * @return 受影响行数；0 表示期间已被他人写入
     */
    @Update("UPDATE ord_order SET status = #{po.status}, subtotal_amount = #{po.subtotalAmount}, "
            + "discount_amount = #{po.discountAmount}, tax_amount = #{po.taxAmount}, "
            + "total_amount = #{po.totalAmount}, refundable_amount = #{po.refundableAmount}, "
            + "version = version + 1, updated_at = #{po.updatedAt} "
            + "WHERE id = #{po.id} AND version = #{expectedVersion}")
    int updateAmountsAndStatusWithVersion(@Param("po") OrderPo po,
                                          @Param("expectedVersion") int expectedVersion);

    /**
     * 状态转换的条件更新：仅当库中状态仍是 {@code expectedStatus} 时写入新状态。
     *
     * <p>{@code ord_order} 没有版本列可用于状态机（{@code OrderPo} 无 {@code @Version}、工程无乐观锁拦截器），
     * 而状态转换的判定列就是状态本身。只靠应用层"读出来比对"不是并发保护：
     * 两个并发确认都读到 {@code DRAFT}、都通过比对、都写入成功——订单被确认两次，各自的后续动作都会执行。
     *
     * @return 受影响行数；0 表示期间已被他人转换
     */
    @Update("UPDATE ord_order SET status = #{newStatus}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND status = #{expectedStatus}")
    int updateStatusGuarded(@Param("id") Long id, @Param("expectedStatus") String expectedStatus,
                            @Param("newStatus") String newStatus,
                            @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 挂单的条件更新：仅当订单**尚未挂单**（{@code hold_at IS NULL}）时写入。
     *
     * <p>判据是 {@code hold_at} 而不是 {@code status}：挂单是 {@code SERVING} 内的标记，**不改商业状态**。
     * 若按 {@code status = 'SERVING'} 做条件，两个并发挂单都会通过——两次挂单各自的理由与时间互相覆盖，
     * 而审计里那条 {@code order-hold:{id}:{holdAt}} 幂等键也随之对不上其中一次。
     *
     * @return 受影响行数；0 表示已被他人挂单
     */
    @Update("UPDATE ord_order SET hold_at = #{holdAt}, hold_reason = #{holdReason}, "
            + "updated_at = #{updatedAt} WHERE id = #{id} AND hold_at IS NULL")
    int holdIfNotHeld(@Param("id") Long id, @Param("holdAt") LocalDateTime holdAt,
                      @Param("holdReason") String holdReason, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 解挂的条件更新：仅当订单**确实处于挂单中**（{@code hold_at IS NOT NULL}）时清空。
     *
     * <p>与 {@link #holdIfNotHeld} 对称。没有这条条件时，两个并发解挂都会"成功"，
     * 而其中一次的审计与真实变更都对不上（第二次其实什么都没改）。
     *
     * @return 受影响行数；0 表示当前并未挂单
     */
    @Update("UPDATE ord_order SET hold_at = NULL, hold_reason = NULL, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND hold_at IS NOT NULL")
    int unholdIfHeld(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 取消/作废的条件更新：仅当库中状态仍是 {@code expectedStatus} 时写入 VOIDED。
     *
     * <p>判据用 {@code status} 而不是 version 是刻意的：取消流程不写金额，而**重复取消是幂等语义**
     * （已 VOIDED 时直接回既有结果）。若按 version 守卫，一次无害的重试会因为"期间有别的写入改过版本"
     * 而失败；按 status 守卫则精确表达"只有仍是原状态才允许作废"。
     *
     * <p>没有这条条件时，两个并发取消都读到同一非终结状态、都通过应用层判定，
     * 于是 {@code cancelByOrder} 被调用两次（两次释放/取消同一会话）、审计写两条，
     * 后写者的原因与时间覆盖前者——而 CALLER 两次都收到成功。
     *
     * @return 受影响行数；0 表示期间已被他人转换
     */
    @Update("UPDATE ord_order SET status = 'VOIDED', cancelled_at = #{cancelledAt}, "
            + "updated_at = #{cancelledAt} WHERE id = #{id} AND status = #{expectedStatus}")
    int voidIfStatus(@Param("id") Long id, @Param("expectedStatus") String expectedStatus,
                     @Param("cancelledAt") LocalDateTime cancelledAt);
}
