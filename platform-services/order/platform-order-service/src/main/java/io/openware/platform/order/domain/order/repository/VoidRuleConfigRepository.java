package io.openware.platform.order.domain.order.repository;

import io.openware.platform.order.domain.order.model.VoidRuleConfig;
import java.util.Optional;

/**
 * 作废审批规则仓储端口。
 *
 * <p>不暴露 MyBatis 的查询语法：定位口径以业务语义表达（{@code findActive} 的
 * 「租户 + 业态 + 门店」就是解析顺序的每一档）。
 *
 * <p><b>并发契约</b>：保存是「读出来判版本 → 带版本条件写回」，
 * {@link #updateRequireApprovalWithVersion(VoidRuleConfig, int)} 必须发**单条**
 * {@code WHERE id = ? AND version = ?} 的条件 UPDATE（版本自增写在 SQL 里）。
 * 返回 {@code empty} 表示期间已有他人写入，调用方必须报版本冲突，而不是把本次值当成生效值：
 * 这条规则决定「作废订单要不要先走审批」，静默覆盖会让门店按 A 的配置解释 B 的请求。
 *
 * <p>{@code created_at}/{@code updated_at} 由适配器按落库时刻填充（记账信息不进领域模型），
 * 需要回给响应时经 {@link Persisted} 携带。本端口的 {@link Persisted}/{@link AuditSnapshot}
 * 就是本包（{@code domain.order.repository}）里那一对共享类型：order 上下文的四个端口
 * （订单 / 明细 / 作废审批 / 本端口）共用同一份「业务聚合 + 记账快照」形状，
 * 不为每个端口重复定义一份（预约上下文保留它自己那一对，不在本次改动范围内）。
 */
public interface VoidRuleConfigRepository {

    /**
     * 按「租户 + 业态 + 门店」取**生效中**（{@code status=ACTIVE}）的规则行。
     *
     * <p>不过滤「门店覆盖必须带业态」之类的语义：{@code store_id = 0} 是租户/业态默认档，
     * 调用方按 门店覆盖 → 业态默认 → 租户默认 的顺序各查一次（与改造前逐字一致）。
     */
    Optional<Persisted<VoidRuleConfig>> findActive(Long tenantId, String businessType, Long storeId);

    /**
     * 按「租户 + 幂等键」取规则行（保存路径的重试语义），**不过滤状态**：
     * 命中已存在的行就原样返回——重复提交不应该因为对方后来被停用而再插一行。
     *
     * <p>幂等键为空/空白时直接返回空（不带幂等键的调用方保持既有行为）。
     */
    Optional<Persisted<VoidRuleConfig>> findByIdempotencyKey(Long tenantId, String idempotencyKey);

    /**
     * 新增规则（调用方给版本 0、状态 {@code ACTIVE}），返回落库后的聚合与记账快照。
     *
     * <p>作用域唯一键与幂等唯一键都由数据库兜底：撞键必须抛出，调用方据此重查返回既有行。
     */
    Persisted<VoidRuleConfig> insert(VoidRuleConfig config);

    /**
     * CAS 覆盖「是否要求审批」：单条
     * {@code UPDATE ... SET require_approval=?, version=version+1, updated_at=? WHERE id=? AND version=?}。
     *
     * <p>{@code tenant_id}/{@code business_type}/{@code store_id}/{@code status}/{@code idempotency_key}
     * 是定位列与作用域列，不在更新集合里：改作用域等于换一行规则，必须新插而不是原地改。
     *
     * @param expectedVersion 调用方读出来的版本号（只作为条件）
     * @return 落库结果；{@code empty} = 版本冲突（0 行），调用方必须报冲突
     */
    Optional<Persisted<VoidRuleConfig>> updateRequireApprovalWithVersion(VoidRuleConfig config,
                                                                        int expectedVersion);
}
