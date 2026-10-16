package io.openware.platform.order.domain.ktv.repository;

import io.openware.platform.order.domain.ktv.model.KtvSession;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * KTV 包厢会话仓储端口。
 *
 * <p>不暴露 MyBatis 的 {@code Wrapper} / 持久化对象 / {@code Mapper}：查询条件以业务语义命名
 * （{@code findOpenSessions} / {@code findReservedOverdue} / {@code findOpenedBetween}），
 * 调用方看不到查询语法。
 *
 * <p><b>并发语义是本端口最重要的部分，实现必须逐条保住，不得「顺手简化」</b>：
 * {@code ord_ktv_session} <b>没有</b> MyBatis-Plus 的 {@code @Version} 保护，工程里也<b>没有</b>
 * {@code OptimisticLockerInnerInterceptor}，因此乐观锁完全依赖下面这三条语句里的显式
 * {@code WHERE id = ? AND version = ?}：
 * <ul>
 *   <li>{@link #updateWithVersion(KtvSession)} —— 状态机全部流转（开台/暂停/恢复/取消/结台/暂停修正）
 *       的唯一写回入口；</li>
 *   <li>{@link #transferRoom(KtvSession)} —— 转台换包厢；</li>
 *   <li>版本自增写在 SQL 里（{@code version = version + 1}），不是「先读再写版本号」——
 *       后者会在并发期间覆盖别人推进过的版本。</li>
 * </ul>
 * 受影响 0 行是**并发冲突信号**，由调用方转成明确错误（{@code SESSION_VERSION_CONFLICT}），
 * 绝不允许适配器吞掉。
 *
 * <p>唯一一条<b>不带条件</b>的写是 {@link #update(KtvSession)}：它保留迁移前
 * {@code KtvSessionMapper#updateById} 的实义（转台成功后回写新的占用 ID）。详见该方法说明。
 */
public interface KtvSessionRepository {

    /**
     * 按标识取会话。
     *
     * <p>查询走租户行拦截器：返回空既可能是「不存在」也可能是「跨租户」，调用方用
     * {@link #findTenantIdById(Long)} 区分（后者忽略租户过滤，只读 {@code tenant_id} 一列）。
     */
    Optional<KtvSession> findById(Long id);

    /**
     * 跨租户鉴别：忽略租户拦截，仅按主键读 {@code tenant_id}。
     *
     * <p>返回 {@code null} = 该主键不存在；非空但 {@link #findById(Long)} 为空 = 跨租户。
     * 定时任务也用不到本方法（它按行自带 tenantId 逐个设上下文）。
     */
    Long findTenantIdById(Long id);

    /** 按订单取**最近一次**会话（{@code ORDER BY id DESC LIMIT 1}，转台/作废等按订单命令驱动）。 */
    Optional<KtvSession> findLatestByOrderId(Long orderId);

    /**
     * 按订单取**全部**会话（账单用）。
     *
     * <p>与 {@link #findLatestByOrderId(Long)} 是两件事：一个订单可能多次开台，账单必须拿到全部行
     * 再按「当前这一次」挑选，取最新一条会把上一次开台的结果当成本次消费展示。
     * 结果不排序（挑选规则只看状态与 id，与行序无关）。
     */
    List<KtvSession> listByOrderId(Long orderId);

    /**
     * 批量按订单取会话（订单列表/待确认加项投影用，一次查询避免 N+1）。
     *
     * <p>{@code orderIds} 为空时返回空列表：空集合不能下推（会拼出非法的 {@code IN ()}）。
     */
    List<KtvSession> listByOrderIds(List<Long> orderIds);

    /**
     * 「开台中」（OPEN / PAUSED）会话：房费刷新定时任务用。
     *
     * <p>定时任务线程没有租户上下文，实现必须忽略租户行过滤一次扫出；每行自带 tenantId，
     * 调用方逐行设上下文再写，绝不跨租户写。
     */
    List<KtvSession> findOpenSessions();

    /**
     * 「已预留但一直没开台」的会话：超时释放任务用。
     *
     * <p>判定口径：{@code status = 'RESERVED'} 且从未开台（{@code opened_at IS NULL}），
     * 参考时间取**预约到店时间**优先、没有则退回**创建时间**（C 端预留 / 后台预约开台两条路
     * 都可能不写 {@code reserved_start_at}），加宽限期后仍早于 {@code cutoff} 即视为未到店。
     *
     * <p>与 {@link #findOpenSessions()} 同范式跨租户一次扫出，逐行设上下文再写，绝不跨租户写。
     */
    List<KtvSession> findReservedOverdue(LocalDateTime cutoff);

    /**
     * 经营报表：某租户在 {@code [fromInclusive, toExclusive)} 内**开过台**的会话（按 {@code opened_at}）。
     *
     * <p>时间条件直接落在 {@code opened_at} 列上、不用函数包裹，保持索引可用；半开区间由实现翻译为
     * {@code >= from} 与 {@code < to}。未开台（{@code opened_at IS NULL}）的会话不在结果里。
     */
    List<KtvSession> findOpenedBetween(Long tenantId, LocalDateTime fromInclusive, LocalDateTime toExclusive);

    /**
     * 新增预留会话，返回落库后的聚合（含回填主键）。
     *
     * <p>审计四列（{@code created_at} / {@code updated_at}）由适配器在落库时填成「现在」——
     * 它们是记账信息、不是业务事实，因此既不在领域模型上，也不是端口的参数（standards/10）。
     */
    KtvSession insert(KtvSession session);

    /**
     * 条件写入（CAS）：{@code WHERE id = ? AND version = ?}，版本自增在 SQL 里。
     *
     * <p>返回 {@code false} 表示受影响 0 行 = 期间已被他人写入，调用方必须转成明确冲突、不得静默成功。
     * 写入的列集合固定为「状态机可变列」：状态、开台/结台时刻、计费单位、计费起点、免费等待、
     * 累计暂停、暂停起点、超时费率、规则快照、占用 ID、包厢名称/编码快照、人数、服务人员快照
     * （会话创建后不变的 {@code tenant_id} / {@code order_id} / {@code reserved_*} 不在其中）。
     */
    boolean updateWithVersion(KtvSession session);

    /**
     * 转台：换包厢与占位/快照（计时继承、不重置），同样是 {@code WHERE id = ? AND version = ?} 的 CAS。
     *
     * <p>返回 {@code false} = 并发冲突（受影响 0 行），调用方必须转成明确错误。
     */
    boolean transferRoom(KtvSession session);

    /**
     * 无条件整实体写入：**只用于转台成功后回写新的资源占用 ID**。
     *
     * <p>为什么要留一条没有 CAS 的写：转台先在资源域腾出旧占用、再登记新占用，
     * 拿到新占用 ID 时 {@link #transferRoom(KtvSession)} 已经提交过了，此时没有第二个可用的条件列
     * （{@code occupation_id} 本身就是要写的目标）。迁移前这里就是一条
     * {@code KtvSessionMapper#updateById}，本端口逐字保留它，没有顺手加条件。
     *
     * <p><b>已知缺陷（迁移前就存在，刻意不在这里修）</b>：{@code updateById} 会把实体上所有非空字段
     * 写进 SET，其中包含 {@code version}——而调用方手里的版本号仍是转台前的值（转台只改了库里的版本），
     * 于是这次回写会把 {@code version} 退回一格。修它需要先决定「转台后的正确版本号是什么」
     * （在内存里推进？重新读？），那会改变转台的并发语义，超出本次迁移的范围，故记录在此并保持原行为。
     */
    void update(KtvSession session);

    /**
     * 回写「谁在服务本包厢」到会话（{@code server_id} + {@code server_name}）。
     *
     * <p>名称来自资源域；资源服务不可达时传 {@code null}，语句用 {@code COALESCE} 保留既有名称，
     * 避免一次外部抖动把已固化的服务人员名清空。
     *
     * <p>这是**单列条件之外的独立语句**，不参与会话状态机的 CAS：它只写两个快照列 + 更新时间，
     * 与状态流转互不覆盖，因此不需要版本条件（迁移前的语句也没有）。
     * 审计列 {@code updated_at} 由适配器在同一条语句里刷成「现在」，不是端口的参数。
     */
    void updateServerSnapshot(Long sessionId, Long serverResourceId, String serverName);
}
