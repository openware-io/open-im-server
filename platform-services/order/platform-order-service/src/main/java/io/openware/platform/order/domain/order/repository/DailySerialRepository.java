package io.openware.platform.order.domain.order.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 单据号每日序号仓储端口（{@code ord_daily_serial}）。
 *
 * <p>三条语句的语义由 {@code DailySerialNumberGenerator} 在**同一个事务**里组合，
 * 靠数据库行锁（UPDATE 持有到提交）保证「自增 → 读回」之间没有别的会话插入：
 * <ol>
 *   <li>{@link #incrementIfPresent}：行已存在时自增并占用排他锁；</li>
 *   <li>{@link #insertFirstIfAbsent}：行不存在时建行（当日第 1 单）；并发下由唯一键只放行一个事务；</li>
 *   <li>{@link #currentSequence}：读回本事务刚分配到的值。</li>
 * </ol>
 *
 * <p><b>为什么三个显式方法而不是一个 {@code nextSequence}</b>：「先自增、被抢则建行、再重试」的
 * 并发策略与失败关闭语义属于应用层（见 {@code DailySerialNumberGenerator}），端口只承载三条持久化动作；
 * 把它合并会把重试与错误码一起搬进适配器，反而让「失败关闭、绝不降级发号」的规则离调用点更远。
 *
 * <p><b>租户条件必须显式带上</b>：发号发生在单据落库之前，调用上下文可能不完整，
 * 因此这三条语句都忽略租户行拦截、显式按 {@code tenant_id} 定位（与改造前逐字一致）；
 * 否则会出现「拦截器再追加一次 tenant_id」或「无上下文时查不到行而静默重发 0001」。
 */
public interface DailySerialRepository {

    /**
     * 行已存在则自增（并持有该行排他锁直到当前事务提交/回滚）。
     *
     * @return {@code true} = 自增成功；{@code false} = 该「租户 + 单据类型 + 营业日」还没有行
     */
    boolean incrementIfPresent(Long tenantId, String businessType, LocalDate businessDate, LocalDateTime at);

    /**
     * 建行并直接占用当日第 1 单（{@code current_seq = 1}）。
     *
     * <p>并发下唯一键只允许一个事务成功；适配器把「撞唯一键」翻译成 {@code false}
     * （而不是把 Spring 的 {@code DuplicateKeyException} 抛给应用层），调用方据此在下一轮重试自增。
     *
     * @return {@code true} = 本次建行成功（拿到当日第 1 单）；{@code false} = 被并发事务抢先建行
     */
    boolean insertFirstIfAbsent(Long tenantId, String businessType, LocalDate businessDate, LocalDateTime at);

    /**
     * 读回当前已分配到的序号（只能在自增/建行所在的那个事务内调用，否则可能读到别的会话的值）。
     *
     * @return 当前序号；{@code empty} = 行不存在（自增成功后读不到属于异常态，调用方必须失败关闭）
     */
    Optional<Long> currentSequence(Long tenantId, String businessType, LocalDate businessDate);
}
