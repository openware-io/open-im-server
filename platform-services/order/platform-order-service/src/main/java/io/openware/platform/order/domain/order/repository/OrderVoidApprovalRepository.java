package io.openware.platform.order.domain.order.repository;

import io.openware.platform.order.domain.order.model.OrderVoidApproval;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 订单作废审批仓储端口。
 *
 * <p>{@code ord_order_void_approval} 的真实列见 {@link OrderVoidApproval}；
 * {@code created_at}/{@code updated_at} 由适配器填充（记账信息不进领域模型），
 * 响应装配需要的值经 {@link Persisted} 携带。
 *
 * <p><b>并发契约</b>：审批动作是「读出来判状态与版本 → 带条件写回」，
 * {@link #reviewWithVersion} 与 {@link #markExecuted} 必须发**单条**带条件的 UPDATE：
 * 前者带 {@code AND version = ? AND status = 'PENDING'}，后者带 {@code AND status = 'APPROVED'}。
 * 返回 {@code empty}/{@code false} 表示期间已被他人处理：调用方必须报 409 冲突或幂等返回，
 * 绝不能把 0 行当成成功（否则一次申请会被两个人同时「批准」，各自触发一次订单作废）。
 */
public interface OrderVoidApprovalRepository {

    /** 按「租户 + 门店 + 标识」取审批（范围内查找：跨门店/跨租户读不到，调用方按 404 处理）。 */
    Optional<Persisted<OrderVoidApproval>> findScoped(Long tenantId, Long storeId, Long id);

    /** 按标识取审批（租户行拦截器生效；CAS 成功后的重新读回也用它）。 */
    Optional<Persisted<OrderVoidApproval>> findById(Long id);

    /**
     * 按「租户 + 幂等键」取审批（申请路径的重试语义），**不过滤状态**：
     * 命中就原样返回既有申请。
     *
     * <p>幂等键为空/空白时直接返回空（不带幂等键的调用方保持既有行为）。
     */
    Optional<Persisted<OrderVoidApproval>> findByIdempotencyKey(Long tenantId, String idempotencyKey);

    /**
     * 审批列表（当前门店），按**申请时间倒序**；{@code status} 为空表示不按状态筛。
     *
     * <p>排序落在 {@code created_at} 列上：它是记账列，但「最近的申请在最前」是这条读路径的业务口径，
     * 因此由适配器负责，而不是把它暴露成端口参数。
     */
    List<Persisted<OrderVoidApproval>> list(Long tenantId, Long storeId, String status);

    /**
     * 新增审批申请（状态 {@code PENDING}、版本 0），返回落库后的聚合与记账快照。
     *
     * <p>{@code created_at}/{@code updated_at} 由适配器填成「现在」；{@code created_by}/{@code updated_by}
     * 不写（DDL 是 {@code NOT NULL DEFAULT 0}，改造前的实现也没写这两列）——迁移不顺手新增审计列写入。
     */
    Persisted<OrderVoidApproval> insert(OrderVoidApproval approval);

    /**
     * CAS 写入审批结果：单条
     * {@code UPDATE ... SET status=?, approver_id=?, review_comment=?, reviewed_at=?, updated_at=?,
     * version=? WHERE id=? AND tenant_id=? AND version=? AND status='PENDING'}。
     *
     * <p>状态判据固定为 {@code PENDING}：只有待审批的申请能被审批，
     * 调用方无法借此绕过状态机（例如把已执行的申请再改回 APPROVED）。
     *
     * @param targetStatus    目标状态（{@code APPROVED} / {@code REJECTED}）
     * @param expectedVersion 读出来的版本号（只作为条件；写回的是它 +1，与改造前的语句逐字一致）
     * @return 落库后的最新行；{@code empty} = 受影响 0 行（已被并发处理），调用方必须报冲突
     */
    Optional<Persisted<OrderVoidApproval>> reviewWithVersion(Long id, Long tenantId, int expectedVersion,
                                                             String targetStatus, Long approverId, String comment,
                                                             LocalDateTime reviewedAt);

    /**
     * 标记「订单作废已执行」：单条
     * {@code UPDATE ... SET status='EXECUTED', updated_at=?, version=? WHERE id=? AND status='APPROVED'}。
     *
     * <p>判据是 {@code status='APPROVED'} 而不是版本：执行是审批通过后的**收尾**，
     * 重复执行（重试）本就应当无害；条件语句保证只有「已通过但还没执行」的行会被标记。
     *
     * @param currentVersion 读出来的版本号（写回它 +1，与改造前的语句逐字一致）
     * @return 是否写入成功；{@code false} = 状态已不是 APPROVED（或行已被删除），
     *         调用方保持改造前的行为：不抛错，仍按「已执行」返回本次结果
     */
    boolean markExecuted(Long id, int currentVersion, LocalDateTime at);
}
