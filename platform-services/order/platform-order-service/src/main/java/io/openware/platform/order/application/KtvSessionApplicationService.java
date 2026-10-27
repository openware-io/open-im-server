package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.currency.Currency;
import io.openware.platform.order.domain.ktv.model.KtvPricingPlan;
import io.openware.platform.order.domain.ktv.model.KtvSession;
import io.openware.platform.order.domain.ktv.model.KtvSessionStatus;
import io.openware.platform.order.domain.ktv.repository.KtvSessionRepository;
import io.openware.platform.order.domain.ktv.service.KtvRoomFeeCalculator;
import io.openware.platform.order.domain.ktv.repository.KtvPricingPlanProvider;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.OrderItem;
import io.openware.platform.order.domain.order.model.TradeStatus;
import io.openware.platform.order.domain.order.repository.OrderItemQuery;
import io.openware.platform.order.domain.order.repository.OrderItemRepository;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.repository.Persisted;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.openware.platform.order.domain.ktv.model.KtvBillingUnit;
import io.openware.platform.order.domain.ktv.model.KtvRoundingDirection;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * KTV 会话状态机（RESERVED→OPEN→PAUSED(可选)→CLOSED）。
 * 计时不可倒退；开台/结台读取计价方案（暂无 pricing 服务，用可配置默认）并固化规则快照。
 *
 * <p><b>持久化边界</b>：本服务只依赖领域端口 {@link KtvSessionRepository} 与领域模型
 * {@link KtvSession}，不再接触 MyBatis Mapper 或持久化对象。会话的并发保护仍是端口里的
 * {@code UPDATE ... WHERE id = ? AND version = ?}（{@code ord_ktv_session} 没有
 * MyBatis-Plus {@code @Version}，工程里也没有乐观锁拦截器），本服务只负责把「受影响 0 行」
 * 转成明确的 {@code SESSION_VERSION_CONFLICT}。
 */
@Slf4j
@Service
public class KtvSessionApplicationService {
    private final KtvSessionRepository sessionRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final KtvPricingPlanProvider pricingPlanProvider;
    private final AuditClient auditClient;
    private final OrderAmountApplicationService orderAmounts;
    private final ResourceStateClient resourceStateClient;
    /** 结台即结算：结台完成后直接把订单推进到「待收款」（见 {@link #autoSettleAfterClose}）。 */
    private final SettlementApplicationService settlementService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    /** 静态快照解析器：账单等只读路径也要从同一份快照复算金额（见 {@link #planFromSnapshot}）。 */
    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Autowired
    public KtvSessionApplicationService(KtvSessionRepository sessionRepository,
                                        OrderRepository orderRepository,
                                        OrderItemRepository orderItemRepository,
                                        KtvPricingPlanProvider pricingPlanProvider,
                                        AuditClient auditClient,
                                        OrderAmountApplicationService orderAmounts,
                                        ResourceStateClient resourceStateClient,
                                        SettlementApplicationService settlementService) {
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.pricingPlanProvider = pricingPlanProvider;
        this.auditClient = auditClient;
        this.orderAmounts = orderAmounts;
        this.resourceStateClient = resourceStateClient;
        this.settlementService = settlementService;
    }

    /** 兼容既有装配/单元测试：无资源客户端时跳过占用与清洁同步（可用性降级）。 */
    public KtvSessionApplicationService(KtvSessionRepository sessionRepository,
                                        OrderRepository orderRepository,
                                        OrderItemRepository orderItemRepository,
                                        KtvPricingPlanProvider pricingPlanProvider,
                                        AuditClient auditClient,
                                        OrderAmountApplicationService orderAmounts) {
        this(sessionRepository, orderRepository, orderItemRepository, pricingPlanProvider, auditClient, orderAmounts,
                null, null);
    }

    /** 兼容既有装配/单元测试：有资源客户端、但不做结台即结算（结算服务缺省）。 */
    public KtvSessionApplicationService(KtvSessionRepository sessionRepository,
                                        OrderRepository orderRepository,
                                        OrderItemRepository orderItemRepository,
                                        KtvPricingPlanProvider pricingPlanProvider,
                                        AuditClient auditClient,
                                        OrderAmountApplicationService orderAmounts,
                                        ResourceStateClient resourceStateClient) {
        this(sessionRepository, orderRepository, orderItemRepository, pricingPlanProvider, auditClient, orderAmounts,
                resourceStateClient, null);
    }

    /** 包厢名称/编码快照：资源服务不可达时保持原值（订单列表退回只显示包厢 ID）。 */
    private KtvSession applyRoomSnapshot(KtvSession session) {
        return applySnapshot(session, roomSnapshot(session.roomResourceId()));
    }

    /** 读取资源快照（名称/编码/容量/房型与房型单价）；资源服务不可达返回 null（读路径降级）。 */
    private ResourceStateClient.RoomSnapshot roomSnapshot(Long roomResourceId) {
        if (resourceStateClient == null || roomResourceId == null) {
            return null;
        }
        return resourceStateClient.room(roomResourceId).orElse(null);
    }

    /** 快照写入（领域模型不可变，返回新实例）；快照为空时原样返回，保持既有名称不被清空。 */
    private static KtvSession applySnapshot(KtvSession session, ResourceStateClient.RoomSnapshot snapshot) {
        if (snapshot == null) {
            return session;
        }
        return session.withRoomSnapshot(snapshot.name(), snapshot.resourceCode());
    }

    /**
     * 按包厢资源回源取展示名（名称优先、其次编码）：**仅供会话快照为空时的读路径兜底**。
     *
     * <p>为什么需要它：名称/编码快照是**开台那一刻**从资源服务取的（{@link #applyRoomSnapshot}），
     * 开台时资源服务不可达就只留下 {@code roomResourceId}，之后所有「按会话快照展示包厢」的地方
     * （订单列表、客户待确认加项）都会没有包厢名。门店在「待确认加项」里必须知道是哪间包厢的需求，
     * 因此允许回源一次。
     *
     * <p>资源服务仍不可达时返回 {@code null}：读路径降级，绝不抛错打断提醒链路。
     */
    public String roomNameFromResource(Long roomResourceId) {
        ResourceStateClient.RoomSnapshot snapshot = roomSnapshot(roomResourceId);
        if (snapshot == null) {
            return null;
        }
        String name = trimToNull(snapshot.name());
        return name != null ? name : trimToNull(snapshot.resourceCode());
    }

    /** 去空白；空白与 null 一视同仁（快照列可能存了空串）。 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 创建包厢会话：RESERVED（快速开台 createOrder 同步创建，关联 orderId + 包厢 resourceId，打通开台→计时→结台链路）。 */
    @Transactional
    public KtvSession create(Long tenantId, Long orderId, Long roomResourceId) {
        Order order = orderRepository == null || orderId == null ? null : orderRepository.findById(orderId).orElse(null);
        Long storeId = null;
        Long organizationId = null;
        String businessType = null;
        if (order != null) {
            if (!tenantId.equals(order.tenantId())) {
                throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
            }
            storeId = order.storeId();
            organizationId = order.organizationId();
            businessType = order.businessType();
        }
        // 包厢名/编码快照在创建时取一次（资源服务不可达时为空，读路径允许回源一次）。
        ResourceStateClient.RoomSnapshot snapshot = roomSnapshot(roomResourceId);
        KtvSession session = KtvSession.reserved(tenantId, organizationId, storeId, businessType, orderId,
                roomResourceId, snapshot == null ? null : snapshot.name(),
                snapshot == null ? null : snapshot.resourceCode());
        // 审计四列（created_at/updated_at）由适配器在落库时填充：它们是记账信息，不进领域模型。
        try {
            return sessionRepository.insert(session);
        } catch (DuplicateKeyException duplicate) {
            // 一个订单只允许一个会话（V24 uk_ord_ktv_session_order）：并发创建/重试时回放已有会话，
            // 而不是把唯一键冲突抛给调用方（开台/预约开台的重试语义）。
            log.debug("KTV session concurrent insert for order {}, replay existing session", orderId);
            return sessionRepository.findLatestByOrderId(orderId).orElseThrow(() -> duplicate);
        }
    }

    /** 开台（不登记人数）：RESERVED → OPEN。 */
    @Transactional
    public KtvSession open(Long sessionId, Integer freeWaitMinutes) {
        return open(sessionId, freeWaitMinutes, null);
    }

    /**
     * 开台：RESERVED → OPEN，读取计价方案固化计费单位/免费等待/超时费率/规则快照；订单保持 DRAFT（KTV 交易状态未进入结算）。
     *
     * <p>同时固化的还有「本次实际使用的房型与单价」：资源带房型时房费/服务费按房型单价取，缺该房型回退门店级单价，
     * 生效价格连同房型编码/名称一起写进 {@code billing_rule_snapshot_json}（结台写 ROOM_FEE 明细时原样带出）。
     *
     * @param partySize 到店人数；null = 不登记。非空时必须 &gt; 0 且不超过包厢容量，否则 400 且不产生占用
     */
    @Transactional
    public KtvSession open(Long sessionId, Integer freeWaitMinutes, Integer partySize) {
        try {
            return doOpen(sessionId, freeWaitMinutes, partySize);
        } catch (RuntimeException failure) {
            // 开台失败留痕（状态冲突/人数非法/占用失败/落库失败）：/business/** 没有 BFF 兜底。
            recordFailure("order.ktv_session.open", sessionId, failure);
            throw failure;
        }
    }

    private KtvSession doOpen(Long sessionId, Integer freeWaitMinutes, Integer partySize) {
        KtvSession session = require(sessionId);
        if (!session.reserved()) {
            throw new BusinessException("ORDER_STATUS_INVALID", "仅 RESERVED 会话可开台");
        }
        Order order = orderRepository.findById(session.orderId()).orElse(null);
        // 资源快照（名称/编码/容量/房型与房型单价）在占用之前读取：人数上限校验与按房型定价都要用它，
        // 而校验必须在占用登记之前完成，否则非法人数会把占用留在资源侧（订单没开台，房态却显示使用中）。
        ResourceStateClient.RoomSnapshot snapshot = roomSnapshot(session.roomResourceId());
        requirePartySize(partySize, snapshot);
        // 清洁中/使用中的包厢不能开台：占用冲突原本只由 occupy 判定，但「清洁中」没有占用记录，
        // 会从 /ktv/sessions/{id}/open 与「预约开台」绕过快速开台那道门禁直接开台。
        // 与快速开台同一口径（fail-closed：房态服务不可达时 requireState 抛 RESOURCE_STATE_UNAVAILABLE）。
        requireRoomAvailable(session.roomResourceId());
        KtvPricingPlan plan = pricingPlanProvider.resolve(session.tenantId(),
                order == null ? null : order.storeId());
        KtvPricingPlan effective = snapshot == null ? plan
                : plan.forRoomType(snapshot.roomTypeCode(), snapshot.roomTypeName(),
                        snapshot.roomTypeUnitPrice(), snapshot.roomTypeServerUnitPrice());
        int freeWait = freeWaitMinutes != null && freeWaitMinutes >= 0 ? freeWaitMinutes : effective.freeWaitMinutes();
        LocalDateTime now = LocalDateTime.now();
        // 开台即占用包厢（IN_USE）：其他端看到的可用性随之变化。
        // 时段冲突直接拒绝；资源服务不可达/无门店上下文时**失败关闭**——拿不到占用登记就不开台，
        // 否则会出现「订单已开台但资源侧无占用」，可被重复开台并重复计费。
        if (resourceStateClient != null && session.occupationId() == null && session.roomResourceId() != null) {
            Long occupationId = resourceStateClient
                    .occupy(session.roomResourceId(), "ORDER", session.orderId(), now, now.plusHours(24))
                    .orElseThrow(() -> new BusinessException("RESOURCE_STATE_UNAVAILABLE",
                            "房态服务未返回占用登记，已拒绝本次开台，请稍后重试"));
            session = session.withOccupationId(occupationId);
        }
        session = applySnapshot(session, snapshot);
        session = session.withPartySize(partySize)
                .withStatus(KtvSession.STATUS_OPEN)
                .withOpenedAt(now)
                .withBillingUnit(effective.billingUnit().name())
                .withFreeWaitMinutes(freeWait)
                .withOvertimeRate(effective.overtimeRate())
                .withBillingStartAt(now.plusMinutes(freeWait))
                .withBillingRuleSnapshotJson(effective.toSnapshotJson());
        session = applyOptimisticUpdate(session);

        if (order != null) {
            // 开台只推进 KTV 业务状态（会话 OPEN）：订单交易状态保持 DRAFT（尚未进入结算）。
            // 兼容列 status 双写为 SERVING（旧读取方仍看它），最终清理迁移会删掉该列。
            if (order.trade() == TradeStatus.DRAFT) {
                order = order.withTradeStatus(TradeStatus.DRAFT.name(), "SERVING");
            }
            // 开台即计费（2026-09-19）：把房费（含 1 名标准服务人员）写成 ROOM_FEE 明细并重算订单金额。
            // 只写会话不写费用时，开台后的账单/收银只有加项金额、房费要等结台才出现——门店看到的就是「应收 0」。
            // 计费起点 billing_start_at 已含免费等待，因此刚开台的金额与「此刻结台」完全一致。
            upsertRoomFeeItem(session, order, now);
            // 领域模型不可变：重算返回新实例，写库用的是返回值（旧实例的金额是过期的）。
            persistOrderGuarded(orderAmounts.recalculate(order));
        }
        // 高风险写操作（开台）审计：记录操作人、包厢与占用，失败仅告警不阻断开台。
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action("order.ktv_session.open")
                .resourceType("ktv_session").resourceId(String.valueOf(sessionId))
                .resourceName(session.roomNameSnapshot())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .idempotencyKey("ktv-session-open:" + sessionId)
                .detailJson("{\"orderId\":" + session.orderId() + ",\"roomResourceId\":" + session.roomResourceId()
                        + ",\"occupationId\":" + session.occupationId() + ",\"partySize\":" + session.partySize()
                        + ",\"freeWaitMinutes\":" + session.freeWaitMinutes() + "}")
                .build());
        return session;
    }

    /**
     * 开台人数校验：null = 不登记（历史调用保持兼容）；非空必须 &gt; 0，且包厢登记了容量时不得超过容量上限。
     * 包厢未登记容量（res_resource.capacity 为 NULL）时只校验 &gt; 0，避免把「没维护容量」变成无法开台。
     */
    static void requirePartySize(Integer partySize, ResourceStateClient.RoomSnapshot snapshot) {
        if (partySize == null) {
            return;
        }
        if (partySize <= 0) {
            throw new ApiException(400, "PARTY_SIZE_INVALID", "到店人数必须大于 0");
        }
        Integer capacity = snapshot == null ? null : snapshot.capacity();
        if (capacity != null && capacity > 0 && partySize > capacity) {
            throw new ApiException(400, "PARTY_SIZE_INVALID",
                    "到店人数 " + partySize + " 超过包厢容量上限 " + capacity + " 人");
        }
    }

    /**
     * 开台前的房态门禁：包厢必须「启用 + 无有效占用 + 非清洁中」。
     *
     * <p>为什么不能只靠 {@link ResourceStateClient#occupy}：占用冲突能拦住「已被别的会话占用」，
     * 但拦不住**清洁中**（清洁中的包厢没有任何占用记录）。于是 /ktv/sessions/{id}/open、
     * 预约开台这两条路径都能把一间正在打扫的包厢开出去。这里与快速开台
     * （{@code OrderController#createOrder}）用同一道门禁，堵住所有开台入口。
     *
     * <p>房态服务不可达时 {@code requireState} 抛 {@code RESOURCE_STATE_UNAVAILABLE}（fail-closed）；
     * 未装配资源客户端（单测/降级装配）或拿不到房态时跳过本门禁，由后续 occupy 兜底。
     */
    private void requireRoomAvailable(Long roomResourceId) {
        if (resourceStateClient == null || roomResourceId == null) {
            return;
        }
        ResourceStateClient.RoomState state = resourceStateClient.requireState(roomResourceId);
        if (state == null || state.available()) {
            return;
        }
        throw new ApiException(409, "ROOM_UNAVAILABLE",
                "包厢「" + (state.name() == null ? roomResourceId : state.name()) + "」"
                        + (state.reason() == null ? "当前不可开台" : state.reason() + "，暂不可开台")
                        + "；请等待清洁完成或改选其它包厢");
    }

    /** 暂停：OPEN → PAUSED（仅累计 paused_seconds 的入口，计时不可倒退）。 */
    @Transactional
    public KtvSession pause(Long sessionId) {
        try {
            KtvSession session = require(sessionId);
            if (!KtvSession.STATUS_OPEN.equals(session.status())) {
                throw new BusinessException("ORDER_STATUS_INVALID", "仅 OPEN 会话可暂停");
            }
            session = applyOptimisticUpdate(session
                    .withStatus(KtvSession.STATUS_PAUSED)
                    .withPauseStartedAt(LocalDateTime.now()));
            // 暂停会直接影响计时与计费基数，此前成功/失败都没有留痕。
            recordSessionAudit("order.ktv_session.pause", "会话暂停", session, "{\"orderId\":" + session.orderId()
                    + ",\"status\":\"PAUSED\"}");
            return session;
        } catch (RuntimeException failure) {
            recordFailure("order.ktv_session.pause", sessionId, failure);
            throw failure;
        }
    }

    /** 恢复：PAUSED → OPEN。 */
    @Transactional
    public KtvSession resume(Long sessionId) {
        try {
            KtvSession session = require(sessionId);
            if (!KtvSession.STATUS_PAUSED.equals(session.status())) {
                throw new BusinessException("ORDER_STATUS_INVALID", "仅 PAUSED 会话可恢复");
            }
            LocalDateTime now = LocalDateTime.now();
            if (session.pauseStartedAt() != null) {
                long elapsed = Math.max(0,
                        java.time.Duration.between(session.pauseStartedAt(), now).getSeconds());
                session = session.withPausedSeconds(
                        (session.pausedSeconds() == null ? 0 : session.pausedSeconds()) + Math.toIntExact(elapsed));
            }
            session = applyOptimisticUpdate(session
                    .withPauseStartedAt(null)
                    .withStatus(KtvSession.STATUS_OPEN));
            // 恢复即重新开始计费，必须与暂停成对留痕。
            recordSessionAudit("order.ktv_session.resume", "会话恢复", session, "{\"orderId\":" + session.orderId()
                    + ",\"status\":\"OPEN\",\"pausedSeconds\":" + session.pausedSeconds() + "}");
            return session;
        } catch (RuntimeException failure) {
            recordFailure("order.ktv_session.resume", sessionId, failure);
            throw failure;
        }
    }

    /** 取消：RESERVED → CANCELLED（未开台可取消，终止会话），同步取消资源占用。 */
    @Transactional
    public KtvSession cancel(Long sessionId) {
        try {
            KtvSession session = require(sessionId);
            if (!session.reserved()) {
                throw new BusinessException("ORDER_STATUS_INVALID", "仅 RESERVED 会话可取消");
            }
            session = applyOptimisticUpdate(session.withStatus(KtvSession.STATUS_CANCELLED));
            releaseOccupationForCancel(session, true);
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action("order.ktv_session.cancel")
                    .actionLabel("会话取消")
                    .resourceType("ktv_session").resourceId(String.valueOf(sessionId))
                    .resourceName(session.roomNameSnapshot())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"orderId\":" + session.orderId() + ",\"status\":\"CANCELLED\"}")
                    .build());
            return session;
        } catch (RuntimeException failure) {
            recordFailure("order.ktv_session.cancel", sessionId, failure);
            throw failure;
        }
    }

    /**
     * 订单取消/作废时终结其**活动包厢会话**并释放包厢占用（订单取消与既有作废共用本入口，
     * 不另写一套占用表操作）：
     * <ul>
     *   <li>活动会话 = RESERVED / OPEN / PAUSED → CANCELLED（CLOSED/CANCELLED 视为已终结）；</li>
     *   <li>占用释放按会话状态选语义：RESERVED 尚未开台用 {@code cancel}，
     *       OPEN/PAUSED 的占用已是 IN_USE 用 {@code release}（与结台同口径，资源侧才能腾出包厢）；</li>
     *   <li>没有活动会话（含无会话）返回 {@code null}：订单取消因此天然幂等，不重复释放、不重复留痕。</li>
     * </ul>
     *
     * <p>包厢占用不释放会让包厢被门禁永久占住（订单已取消、房态仍是「使用中」），
     * 所以这一步是订单取消的必需部分，而不是可选补偿。
     */
    @Transactional
    public KtvSession cancelByOrder(Long orderId) {
        if (orderId == null) {
            return null;
        }
        KtvSession session = sessionRepository.findLatestByOrderId(orderId).orElse(null);
        if (session == null || !session.active()) {
            return null;
        }
        boolean reserved = session.reserved();
        Long occupationId = session.occupationId();
        session = applyOptimisticUpdate(session.withStatus(KtvSession.STATUS_CANCELLED));
        releaseOccupationForCancel(session, reserved);
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action("order.ktv_session.cancel")
                .resourceType("ktv_session").resourceId(String.valueOf(session.id()))
                .resourceName(session.roomNameSnapshot())
                .idempotencyKey("ktv-session-cancel:" + session.id())
                .detailJson("{\"orderId\":" + session.orderId() + ",\"status\":\"CANCELLED\""
                        + ",\"roomResourceId\":" + session.roomResourceId()
                        + ",\"occupationId\":" + occupationId + "}")
                .build());
        return session;
    }

    /** 取消会话后的占用释放：RESERVED（未开台）用 cancel，OPEN/PAUSED（已 IN_USE）用 release；失败只记录。 */
    private void releaseOccupationForCancel(KtvSession session, boolean reserved) {
        if (resourceStateClient == null || session.occupationId() == null) {
            return;
        }
        if (reserved) {
            resourceStateClient.cancel(session.occupationId());
        } else {
            resourceStateClient.release(session.occupationId());
        }
    }

    /** 结台：OPEN/PAUSED → CLOSED，读取计价方案计算包厢计时费并生成 ROOM_FEE 明细；订单 DRAFT → PENDING_PAYMENT（或已全额收款 → COMPLETED）。 */
    @Transactional
    public KtvSession close(Long sessionId) {
        try {
            return doClose(sessionId);
        } catch (RuntimeException failure) {
            // 结台失败留痕（状态冲突/计价快照非法/落库失败）：结台直接决定账单金额，失败必须可回溯。
            recordFailure("order.ktv_session.close", sessionId, failure);
            throw failure;
        }
    }

    private KtvSession doClose(Long sessionId) {
        KtvSession session = require(sessionId);
        if (!session.ongoing()) {
            throw new BusinessException("ORDER_STATUS_INVALID", "仅 OPEN/PAUSED 会话可结台");
        }
        LocalDateTime now = LocalDateTime.now();
        // 只有「PAUSED 且记了暂停起点」这一支才折算暂停并清空起点：其余情况（含脏数据里
        // status=OPEN 却留着 pause_started_at）保持原样写回，与迁移前的分支逐字对应。
        boolean foldingPause = KtvSession.STATUS_PAUSED.equals(session.status())
                && session.pauseStartedAt() != null;
        if (foldingPause) {
            long elapsed = Math.max(0, java.time.Duration.between(session.pauseStartedAt(), now).getSeconds());
            session = session.withPausedSeconds(
                    (session.pausedSeconds() == null ? 0 : session.pausedSeconds()) + Math.toIntExact(elapsed));
        }
        session = session.withStatus(KtvSession.STATUS_CLOSED).withClosedAt(now);
        if (foldingPause) {
            session = session.withPauseStartedAt(null);
        }
        session = applyOptimisticUpdate(session);

        Order order = orderRepository.findById(session.orderId()).orElse(null);
        if (order != null) {
            // 结台：把同一行 ROOM_FEE 明细更新成最终金额（开台时已写过一遍，这里**不能新增第二行**，否则重复计费）。
            upsertRoomFeeItem(session, order, session.closedAt());
            // 包厢计时费刚落入明细，必须在本实例上重算订单金额后再写库，否则旧金额会覆盖新快照。
            Order recalculated = orderAmounts.recalculate(order);
            if (!recalculated.trade().terminal()) {
                // 结台 = KTV 履约完成：交易状态离开 DRAFT。
                // 先付后结（已全额收款）→ COMPLETED；尚未全额收款 → PENDING_PAYMENT（待收款，金额看 paid_amount）。
                boolean fullyPaid = recalculated.total().signum() > 0
                        && recalculated.paid().compareTo(recalculated.total()) >= 0;
                TradeStatus nextTrade = fullyPaid ? TradeStatus.COMPLETED : TradeStatus.PENDING_PAYMENT;
                recalculated = recalculated.withTradeStatus(nextTrade.name(), nextTrade.legacyStatus());
            }
            order = persistOrderGuarded(recalculated);
        }
        // 高风险写操作（结台）审计：异步占位，失败仅告警不阻断结台（真实实现可改同步或 Outbox 兜底）。
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(session.tenantId())
                .action("order.ktv_session.close")
                .actionLabel("结台")
                .resourceType("ktv_session")
                .resourceId(String.valueOf(sessionId))
                .idempotencyKey("ktv-session-close:" + sessionId)
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson("{\"orderId\":" + session.orderId() + ",\"status\":\"CLOSED\"}")
                .build());
        // 结台释放占用并进入「清洁中」：包厢本身不可再被开台/预约，直到门店确认清洁完成。
        if (resourceStateClient != null) {
            if (session.occupationId() != null) {
                resourceStateClient.release(session.occupationId());
            }
            resourceStateClient.setCleaning(session.roomResourceId(), true);
        }
        if (order != null) {
            autoSettleAfterClose(order);
        }
        return session;
    }

    /**
     * 结台即结算（2026-09-19）：结台已经把包厢计时费写入明细并在本实例上重算过金额，
     * 结算只是按同一口径再汇总一次、把订单推进到「待收款」——中途没有必须人工完成的动作，
     * 所以合并成一步：门店点「结台」后直接进入「收银」，不再多点一次「结算」。
     *
     * <p>唯一例外：客户自助加项还挂在 {@code PENDING_APPROVAL} 时**不自动结算**。
     * 这类加项不计入应付（与账单/结算同一口径），自动结算会让它们从账单里消失（漏收）；
     * 此时保持「待结算」，由门店在订单操作里先确认/拒绝再加一次结算。
     *
     * <p>结算失败（例如版本冲突）会连同本次结台一起回滚：结台与「能不能收钱」是同一笔业务，
     * 允许半成品状态反而会出现「已结台却算不出账单」的坏数据。
     */
    private void autoSettleAfterClose(Order order) {
        if (settlementService == null || order.trade() != TradeStatus.PENDING_PAYMENT) {
            return;
        }
        if (orderAmounts != null && orderAmounts.hasPendingApprovalItems(order.id())) {
            log.info("结台后仍有待确认的客户自助加项，保持待结算（处理后再结算）: orderId={}", order.id());
            return;
        }
        // 版本列缺省 0（历史数据可能为空）：结算按 expectedVersion 做乐观锁，不能把 null 带进去。
        settlementService.settle(order.id(), order.currentVersion());
    }

    /**
     * 写入/刷新订单的包厢计时费明细（ROOM_FEE，订单内唯一一行）。
     *
     * <p>计费口径 = 递增粒度（{@code increment_minutes}）+ 舍入方向（默认让利消费者），
     * 与开台中的实时预估（{@link #liveEstimate}）共用 {@link #calculateRoomFee}，禁止两处各写一套。
     * 新口径下包厢费基数已含 1 名标准服务人员（{@link KtvPricingPlan#billableRoomPricePerIncrement()}），
     * 明细名称随之标注；历史快照（旧口径）保持「包厢计时费」原样。
     *
     * <p>三条路径共用本方法：**开台**（endAt=now，开台即计费）、**开台中刷新**（endAt=now，定时任务）、
     * **结台**（endAt=closed_at，最终金额）。已存在则更新同一行——多行会把房费重复计入订单合计。
     */
    private void upsertRoomFeeItem(KtvSession session, Order order, LocalDateTime endAt) {
        KtvPricingPlan plan = snapshotPlan(session.billingRuleSnapshotJson(), session.tenantId(),
                order.storeId());
        if (!KtvRoomFeeCalculator.durationBillable(plan)) {
            // PACKAGE 为固定时长固定价套餐，特判逻辑后续接入 pricing 服务实现。
            return;
        }
        KtvRoomFeeCalculator.Fee fee = calculateRoomFee(session, plan, endAt);
        String name = roomFeeItemName(plan);
        BigDecimal unitPrice = BigDecimal.valueOf(fee.unitPriceMinor());
        BigDecimal quantity = BigDecimal.valueOf(fee.units());
        BigDecimal totalAmount = BigDecimal.valueOf(fee.amountMinor());
        // 币种快照（16_CURRENCY_CONVENTIONS §5）：房费明细跟随其订单的币种快照。
        String currencyCode = Currency.parse(order.currencyCode()).code();
        String priceSnapshotJson = plan.toSnapshotJson();

        OrderItem existing = findRoomFeeItem(session.orderId());
        if (existing == null) {
            OrderItem item = new OrderItem(
                    null,
                    session.tenantId(),
                    order.storeId(),
                    order.businessType(),
                    session.orderId(),
                    "ROOM_FEE",
                    null,
                    null,
                    name,
                    unitPrice,
                    quantity,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    totalAmount,
                    currencyCode,
                    priceSnapshotJson,
                    OrderItem.STATUS_ACTIVE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
            orderItemRepository.insert(item);
        } else {
            // 已存在则更新同一行（多行会把房费重复计入订单合计）；审计列 updated_at 由适配器刷成「现在」。
            orderItemRepository.update(existing.withPricing(name, unitPrice, quantity, totalAmount,
                    currencyCode, priceSnapshotJson));
        }
    }

    /** 订单已有的房费明细（订单内唯一一行；历史脏数据取最早一行，后续更新都落在它上面）。 */
    private OrderItem findRoomFeeItem(Long orderId) {
        return orderItemRepository.list(OrderItemQuery.firstOfType(orderId, "ROOM_FEE")).stream()
                .findFirst()
                .map(Persisted::aggregate)
                .orElse(null);
    }

    /**
     * 批量取多个订单「已生效（ACTIVE）房费明细金额」的合计（最小货币单位；没有房费明细的订单不在结果里）。
     *
     * <p>为什么需要批量：订单列表/详情投影一次要处理整页订单，逐单调用 {@link #findRoomFeeItem(Long)}
     * 会变成 N+1。这里走 {@link OrderItemRepository#listActiveRoomFeeItemsByOrderIds} 一次查完。
     *
     * <p>金额口径与账单完全一致（{@code BillApplicationService#roomFeeItemsMinor}）：只取 status=ACTIVE
     * 的 ROOM_FEE 明细并按 total_amount 求和；房费在订单内只有一行，历史脏数据多行时一并求和
     * （订单合计的重算口径也是全行求和，两处必须一致，否则实时合计的差额法会把多出来的那行漏算）。
     *
     * <p>订单投影现在改用 {@link #orderAmountSumsByOrderIds(List)}：一次批量查询同时给出「全明细合计」与
     * 「房费明细合计」，而实时合计的回退基数（库内合计为 0 时按明细合计回退）两个数都要用。
     * 本方法保留给只关心房费合计的调用方（含真库回归用例），口径不变。
     */
    public Map<Long, Long> roomFeeItemAmountsByOrderIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            // 空集合不能下推（会拼出非法的 IN ()），直接返回空；调用方按「没有房费明细」处理。
            return Map.of();
        }
        List<OrderItem> items = orderItemRepository.listActiveRoomFeeItemsByOrderIds(orderIds);
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> amounts = new java.util.HashMap<>();
        for (OrderItem item : items) {
            if (item.orderId() != null) {
                amounts.merge(item.orderId(), minorOf(item.totalAmount()), Long::sum);
            }
        }
        return amounts;
    }

    /**
     * 一个订单在「实时合计」口径下需要的两项批量汇总（金额均为最小货币单位整数）：
     * {@code activeItemsMinor} = 全部 ACTIVE 明细合计（订单合计基数回退要用），
     * {@code roomFeeItemsMinor} = 其中 ROOM_FEE 明细合计（差额法要扣掉的库内房费快照）。
     */
    public record OrderAmountSums(long activeItemsMinor, long roomFeeItemsMinor) {
        public static final OrderAmountSums ZERO = new OrderAmountSums(0L, 0L);
    }

    /**
     * 开台中会话的**实时计时与实时房费**（读投影）。
     *
     * <p>这两个值**不在领域模型上**：它们由计价方案在响应期算出，不落库、可以随定价规则变化，
     * 属于视图数据（原持久化对象上的两个 {@code @TableField(exist = false)} 字段已随之移除）。
     *
     * @param elapsedSeconds   已计费时长（秒）
     * @param estimatedRoomFee 当前包厢计时费（最小货币单位）
     */
    public record LiveEstimate(long elapsedSeconds, long estimatedRoomFee) {
    }

    /**
     * 批量取多个订单的「已生效明细合计 + 其中房费明细合计」：订单投影一次查询拿齐两个数
     * （差额法要扣房费快照，回退基数要全明细合计），逐单查就是 N+1。
     *
     * <p>为什么返回明细行而不是 SQL 聚合值：与 {@link OrderItemRepository#listActiveRoomFeeItemsByOrderIds}
     * 同款取舍——金额列是 decimal，回读后在 Java 侧按账单同一个「BigDecimal → 最小货币单位 long」
     * 收敛，避免 SQL 聚合别名在 MySQL / 测试 H2 之间大小写不一致。行口径与账单/订单重算完全一致：
     * 只统计 {@code status=ACTIVE} 的明细（待确认/已拒绝不计入）。
     */
    public Map<Long, OrderAmountSums> orderAmountSumsByOrderIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            // 空集合不能下推（会拼出非法的 IN ()），直接返回空；调用方按「没有明细」处理。
            return Map.of();
        }
        List<OrderItem> items = orderItemRepository.listActiveByOrderIds(orderIds);
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        Map<Long, OrderAmountSums> sums = new java.util.HashMap<>();
        for (OrderItem item : items) {
            if (item.orderId() == null) {
                continue;
            }
            long amount = minorOf(item.totalAmount());
            boolean roomFee = "ROOM_FEE".equals(item.itemType());
            sums.merge(item.orderId(), new OrderAmountSums(amount, roomFee ? amount : 0L),
                    (left, right) -> new OrderAmountSums(
                            left.activeItemsMinor() + right.activeItemsMinor(),
                            left.roomFeeItemsMinor() + right.roomFeeItemsMinor()));
        }
        return sums;
    }

    /**
     * 明细金额（decimal(20,6) 列，存的就是最小货币单位整数）→ long。
     * 只做类型收敛，**不做元/分换算**——与 {@code BillApplicationService#toMinor} 同一口径
     * （订单域金额一律按最小货币单位存储）。
     */
    private static long minorOf(BigDecimal amount) {
        return amount == null ? 0L : amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * 刷新「开台中」（OPEN/PAUSED）会话的房费明细与订单金额，返回处理的会话数。
     *
     * <p>为什么需要：开台即计费后金额随时间增长。账单与看板本身取实时值
     * （{@link #liveEstimate} 与 {@code BillApplicationService} 对 OPEN 会话按「此刻结台」计算），
     * 本方法负责让**库里**的 ROOM_FEE 明细与订单合计也跟上时间——既修复本轮之前开台、明细缺失的存量订单，
     * 也避免长开台订单的合计长期停在旧值。会话与订单状态机仍由各入口维护，这里只碰金额字段。
     *
     * <p>截止时刻走 {@link #billableEndAt}：PAUSED 会话停表在 pause_started_at，与看板实时值、
     * 账单实时值、结台固化四处同一口径（挂单期间库内房费也跟着停表，不再「暂停还在长钱」）。
     */
    @Transactional
    public int refreshOpenRoomFees() {
        List<KtvSession> sessions = sessionRepository.findOpenSessions();
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int refreshed = 0;
        for (KtvSession session : sessions) {
            // 定时任务线程没有租户上下文，而订单/明细的 mapper 走 TenantLineInnerInterceptor（缺上下文直接抛）。
            // 这里按会话自带 tenantId 逐个设置上下文：既满足拦截器，也天然限制「只能写这个租户的行」。
            TenantContext previousContext = TenantContextHolder.get();
            try {
                TenantContextHolder.set(new TenantContext(session.tenantId(), null, null, 0L, 0));
                Order order = orderRepository.findById(session.orderId()).orElse(null);
                if (order == null || order.trade() != TradeStatus.DRAFT) {
                    // 会话残留但订单已进入结算/终结（结台、取消、作废）：交回状态机处理，这里不动金额。
                    continue;
                }
                upsertRoomFeeItem(session, order, billableEndAt(session, now));
                persistOrderGuarded(orderAmounts.recalculate(order));
                refreshed++;
            } catch (RuntimeException failure) {
                log.warn("刷新开台中包厢房费失败（下一轮重试）: sessionId={}, cause={}",
                        session.id(), failure.getMessage());
            } finally {
                if (previousContext == null) {
                    TenantContextHolder.clear();
                } else {
                    TenantContextHolder.set(previousContext);
                }
            }
        }
        return refreshed;
    }

    /** 包厢费明细名：含 1 名服务人员的新口径与旧口径（历史快照）分别标注，账单可解释。 */
    static String roomFeeItemName(KtvPricingPlan plan) {
        return plan.roomFeeIncludesServer() ? "包厢费（含 1 名服务人员）" : "包厢计时费";
    }

    /**
     * 包厢计时费唯一计算入口：结台落库与开台中实时预估都必须调用本方法。
     * endAt 为计费截止时间（结台用 closed_at，预估用当前时间或暂停时刻）。
     */
    static KtvRoomFeeCalculator.Fee calculateRoomFee(KtvSession session, KtvPricingPlan plan,
            LocalDateTime endAt) {
        int paused = session.pausedSeconds() == null ? 0 : session.pausedSeconds();
        long billable = KtvRoomFeeCalculator.billableSeconds(session.billingStartAt(), endAt, paused);
        long standard = KtvRoomFeeCalculator.standardSeconds(session.billingStartAt(), session.reservedEndAt(),
                plan.defaultSessionMinutes());
        return KtvRoomFeeCalculator.calculate(plan, billable, standard);
    }

    /**
     * 计费截止时刻的**唯一定义**（看板实时估算 / 账单实时值 / 定时刷新三条路径必须共用）：
     * <ul>
     *   <li>PAUSED 且已记录暂停起点 → 取 {@code pause_started_at}：暂停期间不计费（与
     *       {@link #doClose} 结台时把暂停段计入 paused_seconds 后按 closed_at 算的结果完全一致）；</li>
     *   <li>其余（OPEN，或 PAUSED 缺暂停起点）→ 取 {@code now}。</li>
     * </ul>
     *
     * <p>为什么必须统一：此前账单与定时刷新都直接用 {@code now}，账单在暂停期间**继续长钱**，
     * 而看板与结台都停在暂停时刻——同一张挂单，看板 ¥8000 / 账单 ¥12000，客人一看就对不上。
     * 计费规则取「停表」这一侧（结台固化的就是它，不是新规则）。
     */
    public static LocalDateTime billableEndAt(KtvSession session, LocalDateTime now) {
        if (session != null && KtvSession.STATUS_PAUSED.equals(session.status())
                && session.pauseStartedAt() != null) {
            return session.pauseStartedAt();
        }
        return now;
    }

    /**
     * 开台中会话的**实时房费**（唯一实现：看板实时估算与账单实时值共用）。
     * 计划不按时长计费（PACKAGE 一口价）时返回 {@code null}：此时没有房费明细、也不产生实时房费，
     * 调用方一律按「合计即库内值」处理，绝不凭空造一条 0 元房费行。
     */
    static KtvRoomFeeCalculator.Fee liveRoomFee(KtvSession session, KtvPricingPlan plan, LocalDateTime now) {
        if (!KtvRoomFeeCalculator.durationBillable(plan)) {
            return null;
        }
        return calculateRoomFee(session, plan, billableEndAt(session, now));
    }

    /** 快照来源的计价方案名（账单可解释：这次计费用的是哪个方案/房型）。 */
    static String planDisplayName(KtvPricingPlan plan) {
        if (plan == null) {
            return null;
        }
        String roomType = trimToNull(plan.appliedRoomTypeName());
        return roomType != null ? roomType : "门店标准价";
    }

    /** 转台：换包厢，计时继承（累计）不重置（KTV_BUSINESS_01 §11.2）；资源占用随之切换。 */
    @Transactional
    public KtvSession transfer(Long sessionId, Long newResourceId) {
        try {
            KtvSession session = require(sessionId);
            if (!session.ongoing()) {
                throw new BusinessException("ORDER_STATUS_INVALID", "仅 OPEN/PAUSED 会话可转台");
            }
            if (newResourceId == null) {
                throw new ApiException(400, "AMOUNT_INVALID", "目标包厢不能为空");
            }
            Long previousOccupationId = session.occupationId();
            session = applyRoomSnapshot(session.withRoomResourceId(newResourceId));
            if (!sessionRepository.transferRoom(session)) {
                throw new BusinessException("SESSION_VERSION_CONFLICT", "KTV 会话已被并发修改，请重试");
            }
            if (resourceStateClient != null) {
                if (previousOccupationId != null) {
                    resourceStateClient.release(previousOccupationId);
                }
                LocalDateTime now = LocalDateTime.now();
                Optional<Long> allocated = resourceStateClient
                        .occupy(newResourceId, "ORDER", session.orderId(), now, now.plusHours(24));
                if (allocated.isPresent()) {
                    session = session.withOccupationId(allocated.get());
                    // 新占用必须落库；这是迁移前那条无条件的 updateById，语义与缺陷都原样保留
                    // （见 KtvSessionRepository#update 的说明）。
                    sessionRepository.update(session);
                }
            }
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action("order.transfer")
                    .actionLabel("订单转台")
                    .resourceType("ktv_session").resourceId(String.valueOf(sessionId))
                    .resourceName(session.roomNameSnapshot())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"orderId\":" + session.orderId() + ",\"previousOccupationId\":"
                            + previousOccupationId
                            + ",\"targetResourceId\":" + newResourceId + "}")
                    .build());
            return session;
        } catch (RuntimeException failure) {
            // 转台失败留痕：换包厢失败会留下「订单还在旧包厢/占用未切换」的中间态，必须可回溯。
            recordFailure("order.transfer", sessionId, failure);
            throw failure;
        }
    }

    /** 暂停修正：店长/财务修正 paused_seconds（KTV_BUSINESS_01 §11.5，写审计）。 */
    @Transactional
    public KtvSession correctPause(Long sessionId, Integer correctedPausedSeconds) {
        try {
            KtvSession session = require(sessionId);
            if (!session.ongoing()) {
                throw new BusinessException("ORDER_STATUS_INVALID", "仅 OPEN/PAUSED 会话可修正暂停");
            }
            if (correctedPausedSeconds == null || correctedPausedSeconds < 0) {
                throw new ApiException(400, "AMOUNT_INVALID", "修正后的暂停秒数不能为负");
            }
            session = applyOptimisticUpdate(session.withPausedSeconds(correctedPausedSeconds));
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(session.tenantId())
                    .action("order.ktv_session.correct_pause")
                    .actionLabel("暂停时长修正")
                    .resourceType("ktv_session")
                    .resourceId(String.valueOf(sessionId))
                    .idempotencyKey("ktv-session-correct-pause:" + sessionId)
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"orderId\":" + session.orderId() + ",\"pausedSeconds\":"
                            + correctedPausedSeconds + "}")
                    .build());
            return session;
        } catch (RuntimeException failure) {
            // 暂停修正是店长/财务的特权动作，失败同样要留痕（谁在什么时候试图改计费基数）。
            recordFailure("order.ktv_session.correct_pause", sessionId, failure);
            throw failure;
        }
    }

    /**
     * KTV 会话写操作失败留痕：动作码与成功路径同码，{@code result=FAILURE} + 稳定 errorCode。
     *
     * <p>审计只走 {@link AuditClient#recordAsync}（失败仅 WARN），业务异常原样抛出：留痕不改变业务结果。
     * 不带幂等键（重复失败各自留痕，也不覆盖成功路径的稳定键）；detail 只留会话 ID，不含金额与操作人姓名。
     */
    private void recordFailure(String action, Long sessionId, RuntimeException failure) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action(action)
                .resourceType("ktv_session")
                .resourceId(sessionId == null ? null : String.valueOf(sessionId))
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"sessionId\":" + sessionId + "}")
                .build());
    }

    /** 会话成功留痕：欠补齐的动作（暂停/恢复）与失败同码，detail 只放检索字段。 */
    private void recordSessionAudit(String action, String actionLabel, KtvSession session, String detailJson) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(session.tenantId())
                .action(action)
                .actionLabel(actionLabel)
                .resourceType("ktv_session").resourceId(String.valueOf(session.id()))
                .resourceName(session.roomNameSnapshot())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson(detailJson)
                .build());
    }

    /** 按订单 ID 批量查会话（订单列表一次查询带出包厢信息，避免 N+1）。 */
    public Map<Long, KtvSession> listByOrderIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        return sessionRepository.listByOrderIds(orderIds)
                .stream()
                .collect(Collectors.toMap(KtvSession::orderId, session -> session,
                        KtvSessionApplicationService::currentSessionOf));
    }

    /**
     * 同一订单存在多个会话（多次开台）时取「**当前这一次**」：OPEN/PAUSED 优先，其次 RESERVED（待开台），
     * 最后才轮到已结束的会话；同级取 id 更大（更晚创建）的那条。
     *
     * <p>这里**不能取第一条**：订单投影（收银台房态看板、订单管理、App 的 orderElapsedSeconds）展示的是
     * 「本次消费」的状态/计时/估算金额，取最早那条会把上一次开台的结果显示出来——门店看到的就是
     * 「多次开台被累计」。
     *
     * <p>可见性：账单（{@code BillApplicationService}）与订单投影必须挑**同一条**会话，
     * 否则同一张单的「包厢费」会一个按本次开台实时算、一个按上一次已结台会话的固化值展示。
     */
    public static KtvSession currentSessionOf(KtvSession first, KtvSession second) {
        int rankFirst = sessionCurrentRank(first);
        int rankSecond = sessionCurrentRank(second);
        if (rankFirst != rankSecond) {
            return rankSecond > rankFirst ? second : first;
        }
        long idFirst = first == null || first.id() == null ? Long.MIN_VALUE : first.id();
        long idSecond = second == null || second.id() == null ? Long.MIN_VALUE : second.id();
        return idSecond > idFirst ? second : first;
    }

    /** 「当前」程度排序：进行中 2 > 待开台 1 > 已结束（CLOSED/CANCELLED）0。 */
    private static int sessionCurrentRank(KtvSession session) {
        if (session == null || session.status() == null) {
            return Integer.MIN_VALUE;
        }
        // 用会话状态枚举做判定：KtvSessionStatus.OPEN.name() 不是编译期常量，不能直接写进 String switch。
        KtvSessionStatus state = session.state();
        return switch (state) {
            case OPEN, PAUSED -> 2;
            case RESERVED -> 1;
            default -> 0;
        };
    }

    /**
     * 从订单的**全部**会话里挑出「当前这一次」：与 {@link #currentSessionOf(KtvSession, KtvSession)}
     * 同一条规则（见其说明），供账单这类一次只能拿到 List 的调用方复用，避免两处各写一套挑选逻辑。
     */
    public static KtvSession currentSessionOf(List<KtvSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return null;
        }
        KtvSession current = null;
        for (KtvSession session : sessions) {
            current = current == null ? session : currentSessionOf(current, session);
        }
        return current;
    }

    /** 按订单 ID 查会话（转台/作废等按订单命令驱动）；开台中附带实时计时与预估计时费。 */
    public KtvSession findByOrderId(Long orderId) {
        return sessionRepository.findLatestByOrderId(orderId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "订单对应的 KTV 会话不存在"));
    }

    /**
     * 开台中的包厢计时费实时估算（订单列表/会话详情展示「包厢计时中 · 预估 ¥X」）。
     * 结台后金额以固化明细为准，不再覆盖。storeId 为空时从订单读取。
     *
     * <p>暂停中（PAUSED）**停表**：截止时刻取 pause_started_at，与结台固化口径一致（见
     * {@link #billableEndAt}）；否则取当前时刻。金额由 {@link #liveRoomFee} 唯一算出，
     * 账单实时值、定时刷新与看板三处共用同一条公式。
     *
     * <p>返回值是**读投影**（原持久化对象上两个 {@code @TableField(exist = false)} 字段的替代）：
     * 非开台中会话、或方案不按时长计费（PACKAGE 一口价）时返回 {@code null}，
     * 调用方按「没有实时估算」处理，绝不凭空造一个 0。
     */
    public LiveEstimate liveEstimate(KtvSession session, Long storeId) {
        if (session == null || !session.ongoing()) {
            return null;
        }
        Long effectiveStoreId = storeId;
        if (effectiveStoreId == null) {
            Order order = session.orderId() == null ? null : orderRepository.findById(session.orderId()).orElse(null);
            effectiveStoreId = order == null ? null : order.storeId();
        }
        KtvPricingPlan plan = snapshotPlan(session.billingRuleSnapshotJson(), session.tenantId(),
                effectiveStoreId);
        // 与结台写库同一条计算路径：预估金额必须等于「此刻结台」的 ROOM_FEE 金额。
        KtvRoomFeeCalculator.Fee fee = liveRoomFee(session, plan, LocalDateTime.now());
        if (fee == null) {
            return null;
        }
        return new LiveEstimate(fee.billableSeconds(), fee.amountMinor());
    }

    /** 乐观锁提交：version 不匹配（受影响 0 行）时抛并发冲突，成功后推进内存版本并返回新实例。 */
    private KtvSession applyOptimisticUpdate(KtvSession session) {
        if (!sessionRepository.updateWithVersion(session)) {
            throw new BusinessException("SESSION_VERSION_CONFLICT", "KTV 会话已被并发修改，请重试");
        }
        return session.withVersion(session.versionOrZero() + 1);
    }

    /**
     * 订单金额/状态的乐观锁提交：拿"刚从库里读出的 version"作为期望版本。
     *
     * <p>会话自身的并发由 {@link #applyOptimisticUpdate} 把关；但**同一订单上的两个不同会话**可以同时通过
     * 各自的会话 CAS，随后两次 {@code recalculate} 都会写订单——后写者覆盖前者的金额快照。
     * 订单侧的写入同样需要条件，{@code ord_order} 的 version 列为此而存在。
     *
     * @throws BusinessException 版本已被他人改写（受影响 0 行），不静默覆盖
     */
    /**
     * 订单金额/状态的乐观锁提交：拿"刚从库里读出的 version"作为期望版本，成功后**返回推进了版本的新实例**
     * （结台即结算要拿这个新版本去调结算，否则结算会用过期版本撞冲突）。
     *
     * <p>会话自身的并发由 {@link #applyOptimisticUpdate} 把关；但**同一订单上的两个不同会话**可以同时通过
     * 各自的会话 CAS，随后两次 {@code recalculate} 都会写订单——后写者覆盖前者的金额快照。
     * 订单侧的写入同样需要条件，{@code ord_order} 的 version 列为此而存在。
     *
     * @throws BusinessException 版本已被他人改写（受影响 0 行），不静默覆盖
     */
    private Order persistOrderGuarded(Order order) {
        int expectedVersion = order.currentVersion();
        if (!orderRepository.updateAmountsAndStatusWithVersion(order, expectedVersion)) {
            throw new BusinessException("ORDER_VERSION_CONFLICT", "订单金额已被并发修改，请重试");
        }
        return order.withVersion(expectedVersion + 1);
    }

    /**
     * 从固化快照还原计价方案（结台写 ROOM_FEE、开台中实时预估、账单回退计算都必须走这里，禁止各自解析一套）。
     *
     * <p>快照里的 {@code roomUnitPrice}/{@code serverPricePerInc} 已经是「实际生效」的价格（含房型价），
     * 还原时必须把房型编码/名称/是否命中房型价与按房型单价映射一并带回来，否则再次
     * {@link KtvPricingPlan#toSnapshotJson()} 写进 ROOM_FEE 明细的 price_snapshot_json 会丢掉「实际使用的房型」。
     *
     * <p>同时还原**计费基数口径**：{@code roomFeeIncludesServer=true} 且快照带 {@code combinedUnitPrice} 时
     * 按新口径（房型 + 服务）计包厢费；历史快照两个字段都没有，保持旧口径只按房型价计，
     * 已开台会话不被追溯涨价。
     */
    private KtvPricingPlan snapshotPlan(String snapshot, Long tenantId, Long storeId) {
        KtvPricingPlan restored = planFromSnapshot(snapshot);
        return restored != null ? restored : pricingPlanProvider.resolve(tenantId, storeId);
    }

    /**
     * 快照 → 计价方案；快照为空/无效时返回 {@code null}（调用方回退当前门店方案）。
     * 账单等只读路径复算金额也必须走本方法，避免用「今天的价」重算历史订单。
     */
    static KtvPricingPlan planFromSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return null;
        }
        try {
            String text = snapshot;
            if (text.startsWith("\"") && text.endsWith("\"")) {
                text = SNAPSHOT_MAPPER.readValue(text, String.class);
            }
            var node = SNAPSHOT_MAPPER.readTree(text);
            // 枚举与递增粒度用 fromCode / 默认值解析：历史快照（V16 之前没有 incrementMinutes/roundingDirection，
            // 也没有房型字段）也能结台，不会因缺字段直接 500。
            KtvPricingPlan restored = new KtvPricingPlan(
                    KtvBillingUnit.fromCode(node.path("billingUnit").asText(null)),
                    node.path("roomUnitPrice").asLong(), node.path("defaultSessionMinutes").asInt(),
                    node.path("freeWaitMinutes").asInt(), new BigDecimal(node.path("overtimeRate").asText("1.0")),
                    node.path("incrementMinutes").asInt(30),
                    KtvRoundingDirection.fromCode(node.path("roundingDirection").asText(null)),
                    node.path("serverPricePerInc").asLong(),
                    unitPriceByRoomType(node.path("unitPriceByRoomType")),
                    textOrNull(node, "roomTypeCode"), textOrNull(node, "roomTypeName"),
                    node.path("roomTypePriceApplied").asBoolean(false));
            // 服务单价（每计费单位）原样还原，保证 ROOM_FEE 的 price_snapshot_json 与开台时固化的分项一致；
            // 历史快照没有该字段时保留兼容构造按递增价反推的结果。
            long serverUnitPrice = node.path("serverUnitPrice").asLong(0L);
            // 计费基数口径：必须同时有 roomFeeIncludesServer=true 与 combinedUnitPrice>0 才走新口径，
            // 否则（历史快照）按旧口径 roomUnitPrice 计。
            boolean roomFeeIncludesServer = node.path("roomFeeIncludesServer").asBoolean(false)
                    && node.path("combinedUnitPrice").asLong(0L) > 0L;
            return serverUnitPrice > 0
                    ? restored.withSnapshotPricing(serverUnitPrice, roomFeeIncludesServer)
                    : restored.withRoomFeeIncludesServer(roomFeeIncludesServer);
        } catch (Exception e) {
            throw new BusinessException("PRICING_SNAPSHOT_INVALID", "计价快照无效，无法结台");
        }
    }

    /** 房型单价映射还原：键为房型编码，值为每计费单位单价（非正数忽略）。 */
    private static java.util.Map<String, Long> unitPriceByRoomType(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, Long> prices = new java.util.LinkedHashMap<>();
        // Jackson 3：properties() 取代已过时的 fields()；返回 Set 而非 Iterator，
        // 因此用 forEach 而非 forEachRemaining，插入顺序与写入口径不变。
        node.properties().forEach(entry -> {
            long price = entry.getValue().asLong(0L);
            if (price > 0) {
                prices.put(entry.getKey(), price);
            }
        });
        return prices;
    }

    /** 取字符串字段：字段缺失或 JSON null 都返回 null（Jackson 的 NullNode.asText() 会给出字面量 "null"）。 */
    private static String textOrNull(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private KtvSession require(Long sessionId) {
        Optional<KtvSession> session = sessionRepository.findById(sessionId);
        if (session.isEmpty()) {
            if (sessionRepository.findTenantIdById(sessionId) != null) {
                throw new ApiException(403, "TENANT_SCOPE_DENIED", "无权访问其他租户的会话");
            }
            throw new BusinessException("ORDER_NOT_FOUND", "KTV 会话不存在");
        }
        return session.get();
    }
}
