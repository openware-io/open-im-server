package io.openware.platform.order.domain.reservation.model;

/**
 * 预约状态机（SAAS_PLATFORM_04 §6.3）。
 * PENDING → CONFIRMED → ARRIVED → CONVERTED；
 * 异常分支：CANCELLED（取消）、NO_SHOW（未到店）。
 *
 * <p>状态的**集合语义**集中在这里：同一组状态被多条写路径复用（占预约名额、包厢时段冲突、
 * 可取消/可改期、可到店、可开台）。改造前散在应用层的是
 * {@code List.of("PENDING", "CONFIRMED", "ARRIVED")} 这样的字符串列表，新增一个状态就有漏改点，
 * 而漏改不会编译失败。放进枚举后，判定方法与状态定义在同一处，读到 {@code occupiesSlot()}
 * 就知道口径是什么。
 */
public enum ReservationStatus {

    PENDING,
    CONFIRMED,
    ARRIVED,
    CANCELLED,
    NO_SHOW,
    CONVERTED;

    /** 到店前：可确认、可取消、可改期、可标记未到店。 */
    public boolean preArrival() {
        return this == PENDING || this == CONFIRMED;
    }

    /**
     * 仍占用预约名额与时段：超订统计、包厢时段冲突、到店分配包厢的前置状态都用这一组。
     *
     * <p>{@code CONVERTED}（已开台）不在其中：预约一旦兑现成会话，包厢占用由资源域承载，
     * 再算一次就是同一间房算两次。
     */
    public boolean occupiesSlot() {
        return preArrival() || this == ARRIVED;
    }

    /** 可开台：已确认（客人已到、门店直接开台）或已到店。 */
    public boolean openTableEligible() {
        return this == CONFIRMED || this == ARRIVED;
    }

    /** 开台时是否**隐含登记到店时间**（CONFIRMED 直接开台：到店事实不能只靠状态推断）。 */
    public boolean impliesArrivalOnOpenTable() {
        return this == CONFIRMED;
    }

    /** 已到店或已开台：取消预约与标记未到店都必须改走「取消订单」，否则会留下订单仍在计时的悬挂。 */
    public boolean arrivedOrConverted() {
        return this == ARRIVED || this == CONVERTED;
    }

    /**
     * 解析库中状态字符串（{@code ord_reservation.status}）。
     *
     * <p>状态是闭集（DDL 注释列出全部取值），未知值只可能是代码与 DDL 不一致：此时抛出而不是
     * 静默退化成一个「其它」状态——静默退化会让状态机把不认识的预约当成可取消的可到店预约。
     */
    public static ReservationStatus fromStored(String stored) {
        if (stored != null) {
            String normalized = stored.trim();
            for (ReservationStatus status : values()) {
                if (status.name().equalsIgnoreCase(normalized)) {
                    return status;
                }
            }
        }
        throw new IllegalStateException("unknown reservation status: " + stored);
    }
}
