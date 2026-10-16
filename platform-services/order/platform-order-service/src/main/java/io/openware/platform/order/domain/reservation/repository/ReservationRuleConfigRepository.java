package io.openware.platform.order.domain.reservation.repository;

import io.openware.platform.order.domain.reservation.model.ReservationRuleConfig;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 预约经营规则仓储端口。
 *
 * <p>不暴露 MyBatis {@code Wrapper} / PO / Mapper：定位口径以业务语义表达
 * （{@link #findActive} 的「租户 + 业态 + 门店」就是解析顺序的每一档）。
 *
 * <p><b>并发契约</b>：规则的保存是「读出来判版本 → 带版本条件写回」，因此
 * {@link #updateWindowsWithVersion} 必须发**单条** {@code WHERE id = ? AND version = ?} 的条件 UPDATE。
 * 返回 {@code empty} 表示期间已有他人写入，调用方必须抛版本冲突而不是把本次值当成生效值：
 * 预约窗口直接决定「客人能不能下单/取消」，静默覆盖会让门店按 A 的配置解释 B 的请求。
 *
 * <p>审计四列不进领域模型（standards/10）：{@code insert}/{@code update} 的
 * {@code created_at}/{@code updated_at} 由适配器按落库时刻填充，需要回给调用方时经
 * {@link Persisted} 携带。
 */
public interface ReservationRuleConfigRepository {

    /**
     * 按租户 + 业态 + 门店取**生效中**（{@code status=ACTIVE}）的规则行。
     *
     * <p>不过滤「门店覆盖必须带业态」之类的语义：{@code store_id = 0} 是租户/业态默认档，
     * 调用方按门店覆盖 → 业态默认 → 租户默认的顺序各查一次（与改造前逐字一致）。
     *
     * <p>返回 {@link Persisted}：解析路径只读聚合，保存接口的响应契约要四个记账列。
     */
    Optional<Persisted<ReservationRuleConfig>> findActive(Long tenantId, String businessType, Long storeId);

    /**
     * 按租户 + 幂等键取规则行（保存路径的重试语义），**不过滤状态**：
     * 命中已存在的行就原样返回，重复提交不应该因为对方后来被停用而再插一行。
     */
    Optional<Persisted<ReservationRuleConfig>> findByIdempotencyKey(Long tenantId, String idempotencyKey);

    /**
     * 新增规则（版本 0、状态 ACTIVE），返回落库后的聚合与记账快照。
     *
     * <p>门店/业态/租户的作用域唯一键 {@code uk_ord_reservation_rule_config_scope} 与幂等唯一键
     * 均由数据库兜底：撞键必须抛出，调用方据此重查返回既有行。
     */
    Persisted<ReservationRuleConfig> insert(ReservationRuleConfig config);

    /**
     * CAS 覆盖三个窗口值：单条
     * {@code UPDATE ... SET advance_minutes=?, cancel_minutes=?, reschedule_minutes=?,
     * version=version+1, updated_at=? WHERE id=? AND version=?}。
     *
     * <p>{@code business_type}/{@code store_id}/{@code status}/{@code idempotency_key} 是定位列与作用域列，
     * 不在更新集合里：改作用域等于换一行规则，必须新插而不是原地改（原地改会让作用域唯一键与已生效配置错位）。
     *
     * @param expectedVersion 调用方读出来的版本号（只作为条件）
     * @return 落库结果；{@code empty} = 版本冲突（0 行），调用方必须抛冲突
     */
    Optional<Persisted<ReservationRuleConfig>> updateWindowsWithVersion(ReservationRuleConfig config,
                                                                       int expectedVersion);

    /**
     * 落库结果：业务聚合 + 记账快照。
     *
     * <p>审计四列是记账信息、不进领域模型（standards/10），但保存接口的响应契约带它们，
     * 因此端口把两者分开交给上层。
     */
    record Persisted<T>(T aggregate, AuditSnapshot audit) {
    }

    /**
     * 记账快照（四个审计列的值）。
     *
     * @param createdBy 创建人
     * @param createdAt 创建时间
     * @param updatedBy 更新人
     * @param updatedAt 更新时间
     */
    record AuditSnapshot(Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
    }
}
