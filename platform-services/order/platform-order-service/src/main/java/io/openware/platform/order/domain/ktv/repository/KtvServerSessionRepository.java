package io.openware.platform.order.domain.ktv.repository;

import io.openware.platform.order.domain.ktv.model.KtvServerSession;
import java.util.List;
import java.util.Optional;

/**
 * 服务人员点单仓储端口。
 *
 * <p>不暴露 MyBatis 的 {@code Wrapper} / 持久化对象 / {@code Mapper}：调用方按业务语义取数
 * （「按包厢会话取本轮点单」/「按订单取点单」），看不到查询语法。
 *
 * <p><b>并发语义</b>：{@code ord_ktv_server_session} <b>没有</b> MyBatis-Plus 的 {@code @Version}
 * 保护，工程里也没有 {@code OptimisticLockerInnerInterceptor}，乐观锁完全依赖
 * {@link #updateWithVersion(KtvServerSession)} 里的显式 {@code WHERE id = ? AND version = ?}
 * 与 SQL 侧自增（{@code version = version + 1}）。受影响 0 行是并发冲突信号，
 * 由调用方转成明确错误，适配器不得吞掉。
 */
public interface KtvServerSessionRepository {

    /**
     * 按标识取点单。
     *
     * <p>查询走租户行拦截器：返回空既可能是「不存在」也可能是「跨租户」，调用方用
     * {@link #findTenantIdById(Long)} 区分。
     */
    Optional<KtvServerSession> findById(Long id);

    /** 跨租户鉴别：忽略租户拦截，仅按主键读 {@code tenant_id}（{@code null} = 主键不存在）。 */
    Long findTenantIdById(Long id);

    /**
     * 同一包厢会话下的全部点单，按创建顺序（id 升序）。
     *
     * <p>免费服务人员名额按创建顺序判定：必须一次取全再排序，逐条判定会把名额判给「最先结束」的那条。
     */
    List<KtvServerSession> listByKtvSessionId(Long ktvSessionId);

    /**
     * 同一订单下的全部点单，按创建顺序（id 升序）。
     *
     * <p>老数据可能没有 {@code ktv_session_id}，免费名额判定退化为按订单分组。
     */
    List<KtvServerSession> listByOrderId(Long orderId);

    /**
     * 新增点单，返回落库后的聚合（含回填主键）。
     *
     * <p>审计四列由适配器在落库时填成「现在」（记账信息，不是业务事实，也不是端口的参数）。
     */
    KtvServerSession insert(KtvServerSession session);

    /**
     * 条件写入（CAS）：{@code WHERE id = ? AND version = ?}，版本自增在 SQL 里。
     *
     * <p>写入状态、开始/结束时刻、时长（秒/分钟）与金额。返回 {@code false} = 受影响 0 行 =
     * 期间已被他人写入，调用方必须转成明确冲突。
     */
    boolean updateWithVersion(KtvServerSession session);
}
