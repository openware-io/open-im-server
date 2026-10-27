package io.openware.platform.order.domain.order.repository;

import io.openware.platform.order.domain.order.model.Order;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 订单仓储端口。
 *
 * <p>不暴露 MyBatis 的 {@code Wrapper} / 持久化对象 / {@code Mapper} / 分页对象：查询以业务语义命名，
 * 应用层只表达「要什么订单」。分页也以 offset/limit 语义表达（本域当前没有分页调用点，
 * 因此端口上也不预留分页参数）。
 *
 * <p><b>列集合只来自真实 DDL</b>（{@code V1__ord_order_baseline.sql} 的 {@code ord_order}）：
 * id / tenant_id / organization_id / store_id / order_no / idempotency_key / business_type / customer_id /
 * status / currency_code / subtotal_amount / discount_amount / tax_amount / total_amount / paid_amount /
 * refundable_amount / completed_at / cancelled_at / hold_reason / hold_at / version
 * + created_by / created_at / updated_by / updated_at。原持久化对象上那 13 个
 * {@code @TableField(exist = false)} 的会话投影字段**不在** {@link Order} 上，也不在本端口的任何签名里：
 * 它们是从 {@code ord_ktv_session} 联表读出的展示数据（见 {@code application.dto.OrderView}）。
 *
 * <p><b>并发契约（本端口最重要的部分）</b>：{@code ord_order} 有 {@code version} 列，
 * 但持久化对象上**没有** {@code @Version} 注解、工程里也**没有** {@code OptimisticLockerInnerInterceptor}，
 * 所以 {@code updateById} 不会附加任何版本条件。下面四个条件写方法因此必须各自发**单条**
 * 带条件的 UPDATE（{@code AND version = ?} 或 {@code AND status = ?} / {@code hold_at IS NULL} /
 * {@code hold_at IS NOT NULL}，版本自增写在 SQL 里），返回 {@code false} 表示受影响 0 行
 * = 期间已被他人写入：调用方**必须**按冲突处理，绝不允许适配器把 0 行吞成成功。
 */
public interface OrderRepository {

    /**
     * 按标识取订单。
     *
     * <p>查询走租户行拦截器：返回空既可能是「不存在」也可能是「跨租户」，
     * 调用方用 {@link #findTenantIdById(Long)} 区分（后者忽略租户过滤，只读 {@code tenant_id} 一列）。
     */
    Optional<Order> findById(Long id);

    /**
     * 批量按标识取订单（一次 {@code IN} 查询，避免逐单回查）。
     *
     * <p>{@code ids} 为空时返回空列表：空集合不能下推（会拼出非法的 {@code IN ()}）。
     * 租户行拦截器同样生效（调用方通常已经用租户上下文限定了范围）。
     */
    List<Order> findByIds(List<Long> ids);

    /**
     * 按「租户 + 幂等键」取订单（快速开台的重复提交回放）。
     *
     * <p>幂等键只在租户内唯一（{@code uk_ord_order_idem} 是 {@code (tenant_id, idempotency_key)}），
     * 因此租户条件是业务语义的一部分、必须显式落在 {@code tenant_id} 列上；
     * 键为空/空白时直接返回空（不带头的调用方保持既有行为，也避免空串匹配到别人的行）。
     */
    Optional<Order> findByIdempotencyKey(Long tenantId, String idempotencyKey);

    /**
     * 按条件列出订单，返回「业务聚合 + 记账快照」。
     *
     * <p>返回记账快照是员工业绩报表的硬需求：它按 {@code created_by} 归集「开单员工」，
     * 而审计列不进领域模型（standards/10）。只关心业务字段的调用方读 {@link Persisted#aggregate()}。
     */
    List<Persisted<Order>> list(OrderQuery query);

    /**
     * 跨租户鉴别：忽略租户拦截，仅按主键读 {@code tenant_id}。
     *
     * <p><b>这是「403 还是 404」的判定依据</b>：先 {@link #findById(Long)} 读不到行时，
     * 若本方法能读到 {@code tenant_id}，说明订单存在但属于别的租户 → 403 {@code TENANT_SCOPE_DENIED}；
     * 本方法也读不到才是真的不存在 → 404 {@code ORDER_NOT_FOUND}。
     * 把两者合并会让一次授权失败被降级成「订单不存在」（既不准确，也让调用方无法区分重试策略）。
     *
     * <p>实现必须忽略租户行过滤（原语句标了 {@code @InterceptorIgnore(tenantLine = "true")}），
     * 否则跨租户的行在这里同样读不到，403 永远发不出来。
     */
    Optional<Long> findTenantIdById(Long id);

    /**
     * 新增订单，返回落库后的聚合（含回填主键）。
     *
     * <p>审计四列由适配器在落库时填充：{@code created_at/updated_at} 取「现在」，
     * {@code created_by/updated_by} 取 {@code operatorId}（{@code null} = 调用方没有可记的操作人，
     * 沿用 DDL 默认 0 = 系统）。记账信息不是业务事实，所以不进领域模型，也不是独立的聚合字段。
     *
     * @param operatorId 本次写入的操作人；快速开台路径改造前不写这两列（保持 null），
     *                   预约开台路径改造前写的是操作人（原样保留）
     */
    Order insert(Order order, Long operatorId);

    /**
     * 金额与状态的条件提交：单条
     * {@code UPDATE ... SET status=?, 五个金额列, version = version + 1, updated_at=? WHERE id=? AND version=?}。
     *
     * <p>列的集合覆盖「订单金额重算 + 状态转换」真正会改动的全部字段（漏掉任一金额列都会让该列静默保留旧值）。
     * 版本自增写在 SQL 里：在 Java 侧「读出来 +1 再写回」会在并发窗口内写回过期版本号。
     *
     * @param expectedVersion 读出来的版本号（只作为条件参与 SQL，不写回）
     * @return 是否写入成功；{@code false} = 期间已被他人写入，调用方必须报冲突
     */
    boolean updateAmountsAndStatusWithVersion(Order order, int expectedVersion);

    /**
     * 交易状态转换的条件更新：单条
     * {@code UPDATE ... SET trade_status=?, status=?, updated_at=? WHERE id=? AND trade_status=?}。
     *
     * <p>判据是**交易状态本身**（订单确认：{@code DRAFT → PENDING_PAYMENT}）：
     * 只靠应用层「读出来比对」不是并发保护，两个并发确认都会通过比对。
     * 兼容列 {@code status} 不参与判定，只随交易状态一起写（收口前双写）。
     *
     * @param newLegacyStatus 兼容列要写的旧值（KTV：开台中 SERVING / 结台待收款 WAITING_SETTLEMENT…）
     * @return 是否写入成功；{@code false} = 期间已被他人转换
     */
    boolean updateTradeStatusGuarded(Long id, String expectedTradeStatus, String newTradeStatus,
                                     String newLegacyStatus, LocalDateTime at);

    /**
     * 挂单的条件更新：单条 {@code UPDATE ... SET hold_at=?, hold_reason=?, updated_at=?
     * WHERE id=? AND hold_at IS NULL}。
     *
     * <p>判据是 {@code hold_at} 而不是交易状态：挂单是开台中的标记、**不改交易状态**，
     * 按状态做条件挡不住并发挂单（两次挂单的理由与时间互相覆盖，审计幂等键也对不上）。
     *
     * @return 是否写入成功；{@code false} = 已被他人挂单
     */
    boolean holdIfNotHeld(Long id, LocalDateTime holdAt, String reason, LocalDateTime at);

    /**
     * 解挂的条件更新：单条 {@code UPDATE ... SET hold_at=NULL, hold_reason=NULL, updated_at=?
     * WHERE id=? AND hold_at IS NOT NULL}（与 {@link #holdIfNotHeld} 对称）。
     *
     * @return 是否写入成功；{@code false} = 当前并未挂单
     */
    boolean unholdIfHeld(Long id, LocalDateTime at);

    /**
     * 取消/作废的条件更新：单条
     * {@code UPDATE ... SET trade_status=?, status=?, cancelled_at=?, updated_at=?
     * WHERE id=? AND trade_status=?}。
     *
     * <p>判据用交易状态而不是 {@code version} 是刻意的：取消流程不写金额，
     * 而重复取消是幂等语义；只有仍是原状态才允许终结，精确表达「这次终结针对的是我刚读到的那个状态」。
     * 目标终态由 {@code newTradeStatus} 给出（取消 → {@code CANCELLED}；审批作废/冲正 → {@code VOIDED}）。
     *
     * @return 是否写入成功；{@code false} = 期间已被他人转换
     */
    boolean voidByTradeStatus(Long id, String expectedTradeStatus, String newTradeStatus,
                              String newLegacyStatus, LocalDateTime at);
}
