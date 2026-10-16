package io.openware.platform.order.domain.order.repository;

/**
 * 「业务聚合 + 记账快照」的读回结果。
 *
 * <p>为什么读路径也要带上记账值：领域模型不承载审计列（standards/10 D2），而响应契约与报表需要它们
 * —— 订单列表要回显 {@code createdBy/createdAt/updatedBy/updatedAt}，员工业绩报表要按 {@code created_by}
 * 归集开单员工；明细侧要 {@code created_at}（待确认加项的提交时间）与 {@code updated_at}
 * （账单里房费明细的「固化时刻」）。端口把两者分开交给上层，而不是把审计列塞进领域模型：
 * 只需业务事实的调用方读 {@link #aggregate()}，要装配响应/报表的调用方再读 {@link #audit()}。
 *
 * <p>与 {@code ReservationRepository.Persisted} 同形（各端口自带自己的包装类型）。
 *
 * @param aggregate 业务聚合
 * @param audit     该行的记账快照
 */
public record Persisted<T>(T aggregate, AuditSnapshot audit) {

    /** 只取业务聚合（调用方不关心记账值时用；{@code null} 安全）。 */
    public static <T> T aggregateOf(Persisted<T> row) {
        return row == null ? null : row.aggregate();
    }
}
