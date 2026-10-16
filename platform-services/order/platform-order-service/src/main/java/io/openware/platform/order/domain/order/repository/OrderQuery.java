package io.openware.platform.order.domain.order.repository;

import java.time.LocalDateTime;

/**
 * 订单查询条件（读路径唯一的条件载体）。
 *
 * <p><b>为什么是记录而不是 Wrapper</b>：端口不暴露 MyBatis 的查询语法（standards/10 端口契约），
 * 应用层只表达「要什么订单」，条件怎么落成 SQL 是适配器的事。
 *
 * <p><b>为什么有两个上界字段</b>：两个真实调用点的比较口径本来就不同，硬合成一个会改变行为：
 * <ul>
 *   <li>{@link #createdAtToInclusive}（{@code <=}）：订单列表的界面时间筛选，
 *       统一口径 {@code TimeRangeParams} 把日期形态的 to 收口到当天末尾，SQL 就是 {@code le}；</li>
 *   <li>{@link #createdAtBefore}（{@code <}）：报表按**营业日**取行，
 *       窗口是半开的 {@code [当天起点, 次日起点)}，SQL 就是 {@code lt}。</li>
 * </ul>
 * 两者互斥（同一查询只应设置一个），字段名直接写明比较语义，避免「+1 纳秒」这类依赖列精度的技巧。
 *
 * <p>**没有**租户条件也是刻意的：租户过滤由 MyBatis-Plus 的租户行拦截器追加，
 * 只有报表这类需要显式指定租户的场景才用 {@link #withTenant(Long)} 再写一次
 * （与改造前的调用点逐字一致）。
 *
 * @param tenantId                 租户（{@code null} = 交给租户行拦截器）
 * @param customerId               会员（{@code null} = 不按会员收窄；C 端「我的订单」与消费者会话的读路径用它）
 * @param createdAtFromInclusive   下单时间下界（含；{@code null} = 不筛）
 * @param createdAtToInclusive     下单时间上界（含；{@code null} = 不筛）
 * @param createdAtBefore          下单时间上界（不含；{@code null} = 不筛）
 * @param ordering                 排序口径（订单列表按创建时间倒序；报表聚合不需要排序）
 */
public record OrderQuery(
    Long tenantId,
    Long customerId,
    LocalDateTime createdAtFromInclusive,
    LocalDateTime createdAtToInclusive,
    LocalDateTime createdAtBefore,
    Ordering ordering) {

    /** 排序口径：只提供真实调用点用到的两种，避免端口出现无人使用的排序选项。 */
    public enum Ordering {
        /** 按 {@code created_at} 倒序（订单列表/我的订单：最新的在最前）。 */
        CREATED_AT_DESC,
        /** 不排序（报表聚合：行序不影响汇总结果，也避免给报表加一次无谓的排序）。 */
        UNSORTED
    }

    /** 无任何条件、不排序（报表由调用方再补租户与时间窗口）。 */
    public static OrderQuery all() {
        return new OrderQuery(null, null, null, null, null, Ordering.UNSORTED);
    }

    /** 无任何条件、按创建时间倒序（订单列表/我的订单的读路径）。 */
    public static OrderQuery newestFirst() {
        return new OrderQuery(null, null, null, null, null, Ordering.CREATED_AT_DESC);
    }

    /** 显式租户条件（报表按租户取营业日订单）。 */
    public OrderQuery withTenant(Long tenantId) {
        return new OrderQuery(tenantId, customerId, createdAtFromInclusive, createdAtToInclusive, createdAtBefore,
            ordering);
    }

    /** 按会员收窄（{@code null} = 不收窄，商户会话与内部调用保持既有行为）。 */
    public OrderQuery withCustomer(Long customerId) {
        return new OrderQuery(tenantId, customerId, createdAtFromInclusive, createdAtToInclusive, createdAtBefore,
            ordering);
    }

    /** 下单时间下界（含）。 */
    public OrderQuery from(LocalDateTime createdAtFromInclusive) {
        return new OrderQuery(tenantId, customerId, createdAtFromInclusive, createdAtToInclusive, createdAtBefore,
            ordering);
    }

    /** 下单时间上界（含）——界面时间筛选的闭区间收口。 */
    public OrderQuery until(LocalDateTime createdAtToInclusive) {
        return new OrderQuery(tenantId, customerId, createdAtFromInclusive, createdAtToInclusive, createdAtBefore,
            ordering);
    }

    /** 下单时间上界（不含）——报表营业日窗口的右开端点。 */
    public OrderQuery before(LocalDateTime createdAtBefore) {
        return new OrderQuery(tenantId, customerId, createdAtFromInclusive, createdAtToInclusive, createdAtBefore,
            ordering);
    }
}
