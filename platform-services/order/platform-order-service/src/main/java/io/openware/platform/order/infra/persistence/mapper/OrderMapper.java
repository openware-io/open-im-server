package io.openware.platform.order.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.order.infra.persistence.po.OrderPo;
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
    @Update("UPDATE ord_order SET status = #{po.status}, subtotal_amount = #{po.subtotalAmount}, "
            + "discount_amount = #{po.discountAmount}, tax_amount = #{po.taxAmount}, "
            + "total_amount = #{po.totalAmount}, refundable_amount = #{po.refundableAmount}, "
            + "version = version + 1, updated_at = #{po.updatedAt} "
            + "WHERE id = #{po.id} AND version = #{expectedVersion}")
    int settleWithVersion(@Param("po") OrderPo po, @Param("expectedVersion") int expectedVersion);
}
