package io.openware.platform.order.domain.ktv.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * KTV 包厢会话（领域模型）：{@code ord_ktv_session} 的聚合根，状态机
 * RESERVED → OPEN → PAUSED(可选) → CLOSED，异常终结 CANCELLED。
 *
 * <p><b>KTV 业务状态的唯一事实来源</b>（{@code ORDER_ROOM_INVENTORY_TARGET.md} §4）：
 * 开台中/挂单中/已结台只看本聚合的 {@link #status}（{@link KtvSessionStatus}），
 * 订单主表只表达交易状态（trade_status/refund_status），不再写 SERVING/WAITING_SETTLEMENT。
 *
 * <p><b>只有真实列，且不含审计字段</b>。审计四列（{@code created_by / created_at / updated_by /
 * updated_at}）是记账信息、不是业务事实（standards/10「聚合装配与持久化契约」），由适配器在写入时
 * 填充、读取时丢弃——与 {@code Product} / {@code Occupation} / {@code WalletAccount} 同一约定。
 *
 * <p><b>非列字段一律不进本模型</b>。{@code elapsedSeconds}/{@code estimatedRoomFee} 等展示值
 * 是**读投影**，由计价方案在响应期算出来，落在响应视图 {@code KtvSessionView} 上。
 *
 * <p><b>{@code organizationId} 是组织快照</b>（V24 新增列 {@code ord_ktv_session.organization_id}）：
 * 创建会话时从订单（{@code ord_order.organization_id}）复制，报表/权限按组织归集时不再跨域回查。
 *
 * <p><b>{@code order_id} 在生产库是唯一键</b>（V24 {@code uk_ord_ktv_session_order}）：一个订单只会
 * 关联一个当前会话；历史上「多次开台」的读取容错（{@code currentSessionOf}）保留给存量只读数据。
 *
 * <p><b>{@code version} 保留在模型上</b>：{@code ord_ktv_session} 没有 MyBatis-Plus 的
 * {@code @Version} 保护（工程里也没有 {@code OptimisticLockerInnerInterceptor}），
 * 并发唯一屏障是显式的 {@code UPDATE ... WHERE id = ? AND version = ?}（见
 * {@code KtvSessionRepository#updateWithVersion}），调用方必须读得到、也带得回版本号。
 *
 * <p>不可变记录：字段变更一律返回新实例（{@code with*}），没有 setter。
 *
 * @param id                       会话标识；{@code null} 表示尚未落库
 * @param tenantId                 租户
 * @param organizationId           组织（创建时从订单快照，用于组织维度归集）
 * @param storeId                  门店（创建时从订单快照）
 * @param businessType             业态（创建时从订单快照）
 * @param orderId                  所属订单（唯一）
 * @param roomResourceId           包厢资源（{@code res_resource.id}；转台会改写）
 * @param occupationId             资源占用 ID（开台占用、结台释放；取消/转台同步处理）
 * @param roomNameSnapshot         包厢名称快照（开台/转台那一刻从资源域取，订单列表与账单不再跨域回查）
 * @param roomCodeSnapshot         包厢编码快照（同上）
 * @param partySize                到店人数（开台登记，{@code null} = 未登记）
 * @param serverId                 服务人员资源 ID 快照（点服务人员时写入本会话）
 * @param serverName               服务人员名称快照；资源服务不可达时为 {@code null}
 * @param reservedStartAt          预约到店时间（可能为空：C 端预留与后台预约开台两条路都可能不写）
 * @param reservedEndAt            预约离店时间（房费「标准时长」基准）
 * @param openedAt                 开台时刻
 * @param closedAt                 结台时刻
 * @param billingUnit              计费单位快照（HOUR / HALF_HOUR / PACKAGE）
 * @param billingStartAt           计费起点（已含免费等待）
 * @param freeWaitMinutes          免费等待分钟数
 * @param pausedSeconds            累计暂停秒数（只增不减；修正入口会写审计）
 * @param pauseStartedAt           本次暂停起点（恢复/结台时折算进 {@code pausedSeconds} 并清空）
 * @param overtimeRate             超时费率快照
 * @param billingRuleSnapshotJson  计价规则快照（JSON 文本；账单复算历史订单必须用它，不能取「今天的价」）
 * @param status                   会话状态（{@link KtvSessionStatus}：RESERVED/OPEN/PAUSED/CLOSED/CANCELLED）
 * @param version                  乐观锁版本号列；CAS 写入时作为期望版本
 */
public record KtvSession(
        Long id,
        Long tenantId,
        Long organizationId,
        Long storeId,
        String businessType,
        Long orderId,
        Long roomResourceId,
        Long occupationId,
        String roomNameSnapshot,
        String roomCodeSnapshot,
        Integer partySize,
        Long serverId,
        String serverName,
        LocalDateTime reservedStartAt,
        LocalDateTime reservedEndAt,
        LocalDateTime openedAt,
        LocalDateTime closedAt,
        String billingUnit,
        LocalDateTime billingStartAt,
        Integer freeWaitMinutes,
        Integer pausedSeconds,
        LocalDateTime pauseStartedAt,
        BigDecimal overtimeRate,
        String billingRuleSnapshotJson,
        String status,
        Integer version) {

    /** 已预留（未开台）。 */
    public static final String STATUS_RESERVED = KtvSessionStatus.RESERVED.name();

    /** 开台中（计时计费进行中）。 */
    public static final String STATUS_OPEN = KtvSessionStatus.OPEN.name();

    /** 挂单/暂停中（停表，不计费）。 */
    public static final String STATUS_PAUSED = KtvSessionStatus.PAUSED.name();

    /** 已结台（终结）。 */
    public static final String STATUS_CLOSED = KtvSessionStatus.CLOSED.name();

    /** 已取消（终结）。 */
    public static final String STATUS_CANCELLED = KtvSessionStatus.CANCELLED.name();

    /**
     * 兼容构造（收口前）：只给旧字段集（无 {@code organizationId}）。
     * 新代码应使用规范构造并从订单写入组织快照；本构造只服务于过渡期测试/旧装配。
     */
    public KtvSession(Long id, Long tenantId, Long storeId, String businessType, Long orderId,
            Long roomResourceId, Long occupationId, String roomNameSnapshot, String roomCodeSnapshot,
            Integer partySize, Long serverId, String serverName, LocalDateTime reservedStartAt,
            LocalDateTime reservedEndAt, LocalDateTime openedAt, LocalDateTime closedAt, String billingUnit,
            LocalDateTime billingStartAt, Integer freeWaitMinutes, Integer pausedSeconds,
            LocalDateTime pauseStartedAt, BigDecimal overtimeRate, String billingRuleSnapshotJson,
            String status, Integer version) {
        this(id, tenantId, null, storeId, businessType, orderId, roomResourceId, occupationId,
                roomNameSnapshot, roomCodeSnapshot, partySize, serverId, serverName, reservedStartAt,
                reservedEndAt, openedAt, closedAt, billingUnit, billingStartAt, freeWaitMinutes, pausedSeconds,
                pauseStartedAt, overtimeRate, billingRuleSnapshotJson, status, version);
    }

    /**
     * 新建预留会话（{@code status=RESERVED}、{@code version=0}、免费等待 0、累计暂停 0）。
     *
     * <p>门店/业态/组织来自订单快照，包厢名/编码来自资源域快照（资源服务不可达时为空，读路径回源一次）。
     */
    public static KtvSession reserved(Long tenantId, Long organizationId, Long storeId, String businessType,
            Long orderId, Long roomResourceId, String roomNameSnapshot, String roomCodeSnapshot) {
        return new KtvSession(null, tenantId, organizationId, storeId, businessType, orderId, roomResourceId,
                null, roomNameSnapshot, roomCodeSnapshot, null, null, null, null, null, null, null, null, null,
                0, 0, null, null, null, STATUS_RESERVED, 0);
    }

    /**
     * 兼容工厂（收口前）：无组织快照。新代码用
     * {@link #reserved(Long, Long, Long, String, Long, Long, String, String)}。
     */
    public static KtvSession reserved(Long tenantId, Long storeId, String businessType, Long orderId,
            Long roomResourceId, String roomNameSnapshot, String roomCodeSnapshot) {
        return reserved(tenantId, null, storeId, businessType, orderId, roomResourceId, roomNameSnapshot,
                roomCodeSnapshot);
    }

    /** 落库后回填主键（返回新实例）。 */
    public KtvSession withId(Long newId) {
        return new KtvSession(newId, tenantId, organizationId, storeId, businessType, orderId, roomResourceId,
                occupationId, roomNameSnapshot, roomCodeSnapshot, partySize, serverId, serverName,
                reservedStartAt, reservedEndAt, openedAt, closedAt, billingUnit, billingStartAt,
                freeWaitMinutes, pausedSeconds, pauseStartedAt, overtimeRate, billingRuleSnapshotJson, status,
                version);
    }

    /** 状态流转（返回新实例）。 */
    public KtvSession withStatus(String newStatus) {
        return new KtvSession(id, tenantId, organizationId, storeId, businessType, orderId, roomResourceId,
                occupationId, roomNameSnapshot, roomCodeSnapshot, partySize, serverId, serverName,
                reservedStartAt, reservedEndAt, openedAt, closedAt, billingUnit, billingStartAt,
                freeWaitMinutes, pausedSeconds, pauseStartedAt, overtimeRate, billingRuleSnapshotJson,
                newStatus, version);
    }

    /** 开台写入（返回新实例）。 */
    public KtvSession withOpenedAt(LocalDateTime newOpenedAt) {
        return new KtvSession(id, tenantId, organizationId, storeId, businessType, orderId, roomResourceId,
                occupationId, roomNameSnapshot, roomCodeSnapshot, partySize, serverId, serverName,
                reservedStartAt, reservedEndAt, newOpenedAt, closedAt, billingUnit, billingStartAt,
                freeWaitMinutes, pausedSeconds, pauseStartedAt, overtimeRate, billingRuleSnapshotJson, status,
                version);
    }

    /** 结台写入（返回新实例）。 */
    public KtvSession withClosedAt(LocalDateTime newClosedAt) {
        return new KtvSession(id, tenantId, organizationId, storeId, businessType, orderId, roomResourceId,
                occupationId, roomNameSnapshot, roomCodeSnapshot, partySize, serverId, serverName,
                reservedStartAt, reservedEndAt, openedAt, newClosedAt, billingUnit, billingStartAt,
                freeWaitMinutes, pausedSeconds, pauseStartedAt, overtimeRate, billingRuleSnapshotJson, status,
                version);
    }

    /** 计费起点写入（返回新实例）。 */
    public KtvSession withBillingStartAt(LocalDateTime newBillingStartAt) {
        return new KtvSession(id, tenantId, organizationId, storeId, businessType, orderId, roomResourceId,
                occupationId, roomNameSnapshot, roomCodeSnapshot, partySize, serverId, serverName,
                reservedStartAt, reservedEndAt, openedAt, closedAt, billingUnit, newBillingStartAt,
                freeWaitMinutes, pausedSeconds, pauseStartedAt, overtimeRate, billingRuleSnapshotJson, status,
                version);
    }

    /** 计费单位快照写入（返回新实例）。 */
    public KtvSession withBillingUnit(String newBillingUnit) {
        return new KtvSession(id, tenantId, organizationId, storeId, businessType, orderId, roomResourceId,
                occupationId, roomNameSnapshot, roomCodeSnapshot, partySize, serverId, serverName,
                reservedStartAt, reservedEndAt, openedAt, closedAt, newBillingUnit, billingStartAt,
                freeWaitMinutes, pausedSeconds, pauseStartedAt, overtimeRate, billingRuleSnapshotJson, status,
                version);
    }

    /** 免费等待分钟数写入（返回新实例）。 */
    public KtvSession withFreeWaitMinutes(Integer newFreeWaitMinutes) {
        return new KtvSession(id, tenantId, organizationId, storeId, businessType, orderId, roomResourceId,
                occupationId, roomNameSnapshot, roomCodeSnapshot, partySize, serverId, serverName,
                reservedStartAt, reservedEndAt, openedAt, closedAt, billingUnit, billingStartAt,
                newFreeWaitMinutes, pausedSeconds, pauseStartedAt, overtimeRate, billingRuleSnapshotJson,
                status, version);
    }

    /** 累计暂停秒数写入（返回新实例）。 */
    public KtvSession withPausedSeconds(Integer newPausedSeconds) {
        return new KtvSession(id, tenantId, organizationId, storeId, businessType, orderId, roomResourceId,
                occupationId, roomNameSnapshot, roomCodeSnapshot, partySize, serverId, serverName,
                reservedStartAt, reservedEndAt, openedAt, closedAt, billingUnit, billingStartAt,
                freeWaitMinutes, newPausedSeconds, pauseStartedAt, overtimeRate, billingRuleSnapshotJson,
                status, version);
    }

    /** 本次暂停起点写入（{@code null} = 清空，恢复/结台时折算完毕，返回新实例）。 */
    public KtvSession withPauseStartedAt(LocalDateTime newPauseStartedAt) {
        return new KtvSession(id, tenantId, organizationId, storeId, businessType, orderId, roomResourceId,
                occupationId, roomNameSnapshot, roomCodeSnapshot, partySize, serverId, serverName,
                reservedStartAt, reservedEndAt, openedAt, closedAt, billingUnit, billingStartAt,
                freeWaitMinutes, pausedSeconds, newPauseStartedAt, overtimeRate, billingRuleSnapshotJson,
                status, version);
    }

    /** 超时费率快照写入（返回新实例）。 */
    public KtvSession withOvertimeRate(BigDecimal newOvertimeRate) {
        return new KtvSession(id, tenantId, organizationId, storeId, businessType, orderId, roomResourceId,
                occupationId, roomNameSnapshot, roomCodeSnapshot, partySize, serverId, serverName,
                reservedStartAt, reservedEndAt, openedAt, closedAt, billingUnit, billingStartAt,
                freeWaitMinutes, pausedSeconds, pauseStartedAt, newOvertimeRate, billingRuleSnapshotJson,
                status, version);
    }

    /** 计价规则快照写入（JSON 文本，序列化由 {@link KtvPricingPlan#toSnapshotJson()} 负责，返回新实例）。 */
    public KtvSession withBillingRuleSnapshotJson(String newBillingRuleSnapshotJson) {
        return new KtvSession(id, tenantId, organizationId, storeId, businessType, orderId, roomResourceId,
                occupationId, roomNameSnapshot, roomCodeSnapshot, partySize, serverId, serverName,
                reservedStartAt, reservedEndAt, openedAt, closedAt, billingUnit, billingStartAt,
                freeWaitMinutes, pausedSeconds, pauseStartedAt, overtimeRate, newBillingRuleSnapshotJson,
                status, version);
    }

    /** 资源占用 ID 写入（开台占用 / 转台换占用，返回新实例）。 */
    public KtvSession withOccupationId(Long newOccupationId) {
        return new KtvSession(id, tenantId, organizationId, storeId, businessType, orderId, roomResourceId,
                newOccupationId, roomNameSnapshot, roomCodeSnapshot, partySize, serverId, serverName,
                reservedStartAt, reservedEndAt, openedAt, closedAt, billingUnit, billingStartAt,
                freeWaitMinutes, pausedSeconds, pauseStartedAt, overtimeRate, billingRuleSnapshotJson, status,
                version);
    }

    /** 到店人数写入（{@code null} = 不登记，返回新实例）。 */
    public KtvSession withPartySize(Integer newPartySize) {
        return new KtvSession(id, tenantId, organizationId, storeId, businessType, orderId, roomResourceId,
                occupationId, roomNameSnapshot, roomCodeSnapshot, newPartySize, serverId, serverName,
                reservedStartAt, reservedEndAt, openedAt, closedAt, billingUnit, billingStartAt,
                freeWaitMinutes, pausedSeconds, pauseStartedAt, overtimeRate, billingRuleSnapshotJson, status,
                version);
    }

    /**
     * 包厢快照写入（名称/编码一次写入，返回新实例）。
     *
     * <p>资源服务不可达时调用方**不要**调本方法：原实现保持已有快照原值，传 {@code null} 会清空已经固化的名字。
     */
    public KtvSession withRoomSnapshot(String newRoomNameSnapshot, String newRoomCodeSnapshot) {
        return new KtvSession(id, tenantId, organizationId, storeId, businessType, orderId, roomResourceId,
                occupationId, newRoomNameSnapshot, newRoomCodeSnapshot, partySize, serverId, serverName,
                reservedStartAt, reservedEndAt, openedAt, closedAt, billingUnit, billingStartAt,
                freeWaitMinutes, pausedSeconds, pauseStartedAt, overtimeRate, billingRuleSnapshotJson, status,
                version);
    }

    /** 转台目标包厢写入（计时继承不重置，返回新实例）。 */
    public KtvSession withRoomResourceId(Long newRoomResourceId) {
        return new KtvSession(id, tenantId, organizationId, storeId, businessType, orderId, newRoomResourceId,
                occupationId, roomNameSnapshot, roomCodeSnapshot, partySize, serverId, serverName,
                reservedStartAt, reservedEndAt, openedAt, closedAt, billingUnit, billingStartAt,
                freeWaitMinutes, pausedSeconds, pauseStartedAt, overtimeRate, billingRuleSnapshotJson, status,
                version);
    }

    /** 组织快照写入（缺失时由创建路径从订单补写，返回新实例）。 */
    public KtvSession withOrganizationId(Long newOrganizationId) {
        return new KtvSession(id, tenantId, newOrganizationId, storeId, businessType, orderId, roomResourceId,
                occupationId, roomNameSnapshot, roomCodeSnapshot, partySize, serverId, serverName,
                reservedStartAt, reservedEndAt, openedAt, closedAt, billingUnit, billingStartAt,
                freeWaitMinutes, pausedSeconds, pauseStartedAt, overtimeRate, billingRuleSnapshotJson, status,
                version);
    }

    /** 版本号写入（CAS 提交成功后推进内存版本，返回新实例）。 */
    public KtvSession withVersion(Integer newVersion) {
        return new KtvSession(id, tenantId, organizationId, storeId, businessType, orderId, roomResourceId,
                occupationId, roomNameSnapshot, roomCodeSnapshot, partySize, serverId, serverName,
                reservedStartAt, reservedEndAt, openedAt, closedAt, billingUnit, billingStartAt,
                freeWaitMinutes, pausedSeconds, pauseStartedAt, overtimeRate, billingRuleSnapshotJson, status,
                newVersion);
    }

    /** 会话状态（强类型；未知/为空按 RESERVED 兜底，读路径不因脏数据 500）。 */
    public KtvSessionStatus state() {
        return KtvSessionStatus.parse(status);
    }

    /** 是否待开台（RESERVED）：占用语义是「预占」，取消时用 cancel 而非 release。 */
    public boolean reserved() {
        return KtvSessionStatus.RESERVED.equals(state());
    }

    /** 是否开台中（OPEN / PAUSED）：只有这两个状态的房费还在随时间增长。 */
    public boolean ongoing() {
        return state().ongoing();
    }

    /** 是否仍占用包厢（RESERVED / OPEN / PAUSED）：订单取消时这三种状态都要释放占用。 */
    public boolean active() {
        return state().active();
    }

    /** 版本号（{@code null} 视为 0：历史数据版本列可能为空，CAS 不能把 null 带进去）。 */
    public int versionOrZero() {
        return version == null ? 0 : version;
    }
}
