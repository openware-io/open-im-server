package com.gvchat.platform.order.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.audit.AuditErrorCodes;
import com.gvchat.infrastructure.currency.Currency;
import com.gvchat.platform.order.domain.ktv.model.KtvPricingPlan;
import com.gvchat.platform.order.domain.ktv.service.KtvRoomFeeCalculator;
import com.gvchat.platform.order.domain.ktv.port.KtvPricingPlanProvider;
import com.gvchat.platform.order.infra.client.ResourceStateClient;
import com.gvchat.platform.order.infra.persistence.mapper.KtvSessionMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.po.KtvSessionPo;
import com.gvchat.platform.order.infra.persistence.po.OrderItemPo;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * KTV 会话状态机（RESERVED→OPEN→PAUSED(可选)→CLOSED）。
 * 计时不可倒退；开台/结台读取计价方案（暂无 pricing 服务，用可配置默认）并固化规则快照。
 */
@Slf4j
@Service
public class KtvSessionApplicationService {
    private final KtvSessionMapper sessionMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final KtvPricingPlanProvider pricingPlanProvider;
    private final AuditClient auditClient;
    private final OrderAmountApplicationService orderAmounts;
    private final ResourceStateClient resourceStateClient;
    /** 结台即结算：结台完成后直接把订单推进到「待收款」（见 {@link #autoSettleAfterClose}）。 */
    private final SettlementApplicationService settlementService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    /** 静态快照解析器：账单等只读路径也要从同一份快照复算金额（见 {@link #planFromSnapshot}）。 */
    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @org.springframework.beans.factory.annotation.Autowired
    public KtvSessionApplicationService(KtvSessionMapper sessionMapper,
                                        OrderMapper orderMapper,
                                        OrderItemMapper orderItemMapper,
                                        KtvPricingPlanProvider pricingPlanProvider,
                                        AuditClient auditClient,
                                        OrderAmountApplicationService orderAmounts,
                                        ResourceStateClient resourceStateClient,
                                        SettlementApplicationService settlementService) {
        this.sessionMapper = sessionMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.pricingPlanProvider = pricingPlanProvider;
        this.auditClient = auditClient;
        this.orderAmounts = orderAmounts;
        this.resourceStateClient = resourceStateClient;
        this.settlementService = settlementService;
    }

    /** 兼容既有装配/单元测试：无资源客户端时跳过占用与清洁同步（可用性降级）。 */
    public KtvSessionApplicationService(KtvSessionMapper sessionMapper,
                                        OrderMapper orderMapper,
                                        OrderItemMapper orderItemMapper,
                                        KtvPricingPlanProvider pricingPlanProvider,
                                        AuditClient auditClient,
                                        OrderAmountApplicationService orderAmounts) {
        this(sessionMapper, orderMapper, orderItemMapper, pricingPlanProvider, auditClient, orderAmounts, null, null);
    }

    /** 兼容既有装配/单元测试：有资源客户端、但不做结台即结算（结算服务缺省）。 */
    public KtvSessionApplicationService(KtvSessionMapper sessionMapper,
                                        OrderMapper orderMapper,
                                        OrderItemMapper orderItemMapper,
                                        KtvPricingPlanProvider pricingPlanProvider,
                                        AuditClient auditClient,
                                        OrderAmountApplicationService orderAmounts,
                                        ResourceStateClient resourceStateClient) {
        this(sessionMapper, orderMapper, orderItemMapper, pricingPlanProvider, auditClient, orderAmounts,
                resourceStateClient, null);
    }

    /** 包厢名称/编码快照：资源服务不可达时保持原值（订单列表退回只显示包厢 ID）。 */
    private void applyRoomSnapshot(KtvSessionPo po) {
        applySnapshot(po, roomSnapshot(po.getRoomResourceId()));
    }

    /** 读取资源快照（名称/编码/容量/房型与房型单价）；资源服务不可达返回 null（读路径降级）。 */
    private ResourceStateClient.RoomSnapshot roomSnapshot(Long roomResourceId) {
        if (resourceStateClient == null || roomResourceId == null) {
            return null;
        }
        return resourceStateClient.room(roomResourceId).orElse(null);
    }

    private static void applySnapshot(KtvSessionPo po, ResourceStateClient.RoomSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        po.setRoomNameSnapshot(snapshot.name());
        po.setRoomCodeSnapshot(snapshot.resourceCode());
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
    public KtvSessionPo create(Long tenantId, Long orderId, Long roomResourceId) {
        LocalDateTime now = LocalDateTime.now();
        KtvSessionPo po = new KtvSessionPo();
        po.setTenantId(tenantId);
        po.setOrderId(orderId);
        po.setRoomResourceId(roomResourceId);
        applyRoomSnapshot(po);
        po.setStatus("RESERVED");
        po.setFreeWaitMinutes(0);
        po.setPausedSeconds(0);
        po.setPauseStartedAt(null);
        po.setVersion(0);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        sessionMapper.insert(po);
        return po;
    }

    /** 开台（不登记人数）：RESERVED → OPEN。 */
    @Transactional
    public KtvSessionPo open(Long sessionId, Integer freeWaitMinutes) {
        return open(sessionId, freeWaitMinutes, null);
    }

    /**
     * 开台：RESERVED → OPEN，读取计价方案固化计费单位/免费等待/超时费率/规则快照；订单 DRAFT/WAITING_ARRIVAL → SERVING。
     *
     * <p>同时固化的还有「本次实际使用的房型与单价」：资源带房型时房费/服务费按房型单价取，缺该房型回退门店级单价，
     * 生效价格连同房型编码/名称一起写进 {@code billing_rule_snapshot_json}（结台写 ROOM_FEE 明细时原样带出）。
     *
     * @param partySize 到店人数；null = 不登记。非空时必须 &gt; 0 且不超过包厢容量，否则 400 且不产生占用
     */
    @Transactional
    public KtvSessionPo open(Long sessionId, Integer freeWaitMinutes, Integer partySize) {
        try {
            return doOpen(sessionId, freeWaitMinutes, partySize);
        } catch (RuntimeException failure) {
            // 开台失败留痕（状态冲突/人数非法/占用失败/落库失败）：/business/** 没有 BFF 兜底。
            recordFailure("order.ktv_session.open", sessionId, failure);
            throw failure;
        }
    }

    private KtvSessionPo doOpen(Long sessionId, Integer freeWaitMinutes, Integer partySize) {
        KtvSessionPo po = require(sessionId);
        if (!"RESERVED".equals(po.getStatus())) {
            throw new BusinessException("ORDER_STATUS_INVALID", "仅 RESERVED 会话可开台");
        }
        OrderPo order = orderMapper.selectById(po.getOrderId());
        // 资源快照（名称/编码/容量/房型与房型单价）在占用之前读取：人数上限校验与按房型定价都要用它，
        // 而校验必须在占用登记之前完成，否则非法人数会把占用留在资源侧（订单没开台，房态却显示使用中）。
        ResourceStateClient.RoomSnapshot snapshot = roomSnapshot(po.getRoomResourceId());
        requirePartySize(partySize, snapshot);
        // 清洁中/使用中的包厢不能开台：占用冲突原本只由 occupy 判定，但「清洁中」没有占用记录，
        // 会从 /ktv/sessions/{id}/open 与「预约开台」绕过快速开台那道门禁直接开台。
        // 与快速开台同一口径（fail-closed：房态服务不可达时 requireState 抛 RESOURCE_STATE_UNAVAILABLE）。
        requireRoomAvailable(po.getRoomResourceId());
        KtvPricingPlan plan = pricingPlanProvider.resolve(po.getTenantId(), order == null ? null : order.getStoreId());
        KtvPricingPlan effective = snapshot == null ? plan
                : plan.forRoomType(snapshot.roomTypeCode(), snapshot.roomTypeName(),
                        snapshot.roomTypeUnitPrice(), snapshot.roomTypeServerUnitPrice());
        int freeWait = freeWaitMinutes != null && freeWaitMinutes >= 0 ? freeWaitMinutes : effective.freeWaitMinutes();
        LocalDateTime now = LocalDateTime.now();
        // 开台即占用包厢（IN_USE）：其他端看到的可用性随之变化。
        // 时段冲突直接拒绝；资源服务不可达/无门店上下文时**失败关闭**——拿不到占用登记就不开台，
        // 否则会出现「订单已开台但资源侧无占用」，可被重复开台并重复计费。
        if (resourceStateClient != null && po.getOccupationId() == null && po.getRoomResourceId() != null) {
            Long occupationId = resourceStateClient
                    .occupy(po.getRoomResourceId(), "ORDER", po.getOrderId(), now, now.plusHours(24))
                    .orElseThrow(() -> new BusinessException("RESOURCE_STATE_UNAVAILABLE",
                            "房态服务未返回占用登记，已拒绝本次开台，请稍后重试"));
            po.setOccupationId(occupationId);
        }
        applySnapshot(po, snapshot);
        po.setPartySize(partySize);
        po.setStatus("OPEN");
        po.setOpenedAt(now);
        po.setBillingUnit(effective.billingUnit().name());
        po.setFreeWaitMinutes(freeWait);
        po.setOvertimeRate(effective.overtimeRate());
        po.setBillingStartAt(now.plusMinutes(freeWait));
        po.setBillingRuleSnapshotJson(effective.toSnapshotJson());
        po.setUpdatedAt(now);
        applyOptimisticUpdate(po);

        if (order != null) {
            if ("DRAFT".equals(order.getStatus()) || "WAITING_ARRIVAL".equals(order.getStatus())) {
                order.setStatus("SERVING");
            }
            // 开台即计费（2026-09-19）：把房费（含 1 名标准服务人员）写成 ROOM_FEE 明细并重算订单金额。
            // 只写会话不写费用时，开台后的账单/收银只有加项金额、房费要等结台才出现——门店看到的就是「应收 0」。
            // 计费起点 billing_start_at 已含免费等待，因此刚开台的金额与「此刻结台」完全一致。
            upsertRoomFeeItem(po, order, now);
            orderAmounts.recalculate(order);
            order.setUpdatedAt(now);
            orderMapper.updateById(order);
        }
        // 高风险写操作（开台）审计：记录操作人、包厢与占用，失败仅告警不阻断开台。
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action("order.ktv_session.open")
                .resourceType("ktv_session").resourceId(String.valueOf(sessionId))
                .resourceName(po.getRoomNameSnapshot())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .idempotencyKey("ktv-session-open:" + sessionId)
                .detailJson("{\"orderId\":" + po.getOrderId() + ",\"roomResourceId\":" + po.getRoomResourceId()
                        + ",\"occupationId\":" + po.getOccupationId() + ",\"partySize\":" + po.getPartySize()
                        + ",\"freeWaitMinutes\":" + po.getFreeWaitMinutes() + "}")
                .build());
        return po;
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

    /** 暂停：OPEN → PAUSED（仅累计 paused_seconds 的入口，计时不可倒退）。 */    @Transactional
    public KtvSessionPo pause(Long sessionId) {
        try {
            KtvSessionPo po = require(sessionId);
            if (!"OPEN".equals(po.getStatus())) {
                throw new BusinessException("ORDER_STATUS_INVALID", "仅 OPEN 会话可暂停");
            }
            LocalDateTime now = LocalDateTime.now();
            po.setStatus("PAUSED");
            po.setPauseStartedAt(now);
            po.setUpdatedAt(now);
            applyOptimisticUpdate(po);
            // 暂停会直接影响计时与计费基数，此前成功/失败都没有留痕。
            recordSessionAudit("order.ktv_session.pause", "会话暂停", po, "{\"orderId\":" + po.getOrderId()
                    + ",\"status\":\"PAUSED\"}");
            return po;
        } catch (RuntimeException failure) {
            recordFailure("order.ktv_session.pause", sessionId, failure);
            throw failure;
        }
    }

    /** 恢复：PAUSED → OPEN。 */
    @Transactional
    public KtvSessionPo resume(Long sessionId) {
        try {
            KtvSessionPo po = require(sessionId);
            if (!"PAUSED".equals(po.getStatus())) {
                throw new BusinessException("ORDER_STATUS_INVALID", "仅 PAUSED 会话可恢复");
            }
            LocalDateTime now = LocalDateTime.now();
            if (po.getPauseStartedAt() != null) {
                long elapsed = Math.max(0, java.time.Duration.between(po.getPauseStartedAt(), now).getSeconds());
                po.setPausedSeconds((po.getPausedSeconds() == null ? 0 : po.getPausedSeconds()) + Math.toIntExact(elapsed));
                po.setPauseStartedAt(null);
            }
            po.setStatus("OPEN");
            po.setUpdatedAt(now);
            applyOptimisticUpdate(po);
            // 恢复即重新开始计费，必须与暂停成对留痕。
            recordSessionAudit("order.ktv_session.resume", "会话恢复", po, "{\"orderId\":" + po.getOrderId()
                    + ",\"status\":\"OPEN\",\"pausedSeconds\":" + po.getPausedSeconds() + "}");
            return po;
        } catch (RuntimeException failure) {
            recordFailure("order.ktv_session.resume", sessionId, failure);
            throw failure;
        }
    }

    /** 取消：RESERVED → CANCELLED（未开台可取消，终止会话），同步取消资源占用。 */
    @Transactional
    public KtvSessionPo cancel(Long sessionId) {
        try {
            KtvSessionPo po = require(sessionId);
            if (!"RESERVED".equals(po.getStatus())) {
                throw new BusinessException("ORDER_STATUS_INVALID", "仅 RESERVED 会话可取消");
            }
            po.setStatus("CANCELLED");
            po.setUpdatedAt(LocalDateTime.now());
            applyOptimisticUpdate(po);
            releaseOccupationForCancel(po, true);
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action("order.ktv_session.cancel")
                    .actionLabel("会话取消")
                    .resourceType("ktv_session").resourceId(String.valueOf(sessionId))
                    .resourceName(po.getRoomNameSnapshot())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"orderId\":" + po.getOrderId() + ",\"status\":\"CANCELLED\"}")
                    .build());
            return po;
        } catch (RuntimeException failure) {
            recordFailure("order.ktv_session.cancel", sessionId, failure);
            throw failure;
        }
    }

    /** 仍需释放占用的会话状态：已开台（OPEN/PAUSED）与未开台（RESERVED）。 */
    private static final java.util.Set<String> ACTIVE_SESSION_STATUSES = java.util.Set.of("RESERVED", "OPEN", "PAUSED");

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
    public KtvSessionPo cancelByOrder(Long orderId) {
        if (orderId == null) {
            return null;
        }
        KtvSessionPo po = sessionMapper.selectByOrderId(orderId);
        if (po == null || !ACTIVE_SESSION_STATUSES.contains(po.getStatus())) {
            return null;
        }
        boolean reserved = "RESERVED".equals(po.getStatus());
        Long occupationId = po.getOccupationId();
        po.setStatus("CANCELLED");
        po.setUpdatedAt(LocalDateTime.now());
        applyOptimisticUpdate(po);
        releaseOccupationForCancel(po, reserved);
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action("order.ktv_session.cancel")
                .resourceType("ktv_session").resourceId(String.valueOf(po.getId()))
                .resourceName(po.getRoomNameSnapshot())
                .idempotencyKey("ktv-session-cancel:" + po.getId())
                .detailJson("{\"orderId\":" + po.getOrderId() + ",\"status\":\"CANCELLED\""
                        + ",\"roomResourceId\":" + po.getRoomResourceId()
                        + ",\"occupationId\":" + occupationId + "}")
                .build());
        return po;
    }

    /** 取消会话后的占用释放：RESERVED（未开台）用 cancel，OPEN/PAUSED（已 IN_USE）用 release；失败只记录。 */
    private void releaseOccupationForCancel(KtvSessionPo po, boolean reserved) {
        if (resourceStateClient == null || po.getOccupationId() == null) {
            return;
        }
        if (reserved) {
            resourceStateClient.cancel(po.getOccupationId());
        } else {
            resourceStateClient.release(po.getOccupationId());
        }
    }

    /** 结台：OPEN/PAUSED → CLOSED，读取计价方案计算包厢计时费并生成 ROOM_FEE 明细；订单 SERVING → WAITING_SETTLEMENT。 */
    @Transactional
    public KtvSessionPo close(Long sessionId) {
        try {
            return doClose(sessionId);
        } catch (RuntimeException failure) {
            // 结台失败留痕（状态冲突/计价快照非法/落库失败）：结台直接决定账单金额，失败必须可回溯。
            recordFailure("order.ktv_session.close", sessionId, failure);
            throw failure;
        }
    }

    private KtvSessionPo doClose(Long sessionId) {
        KtvSessionPo po = require(sessionId);
        if (!"OPEN".equals(po.getStatus()) && !"PAUSED".equals(po.getStatus())) {
            throw new BusinessException("ORDER_STATUS_INVALID", "仅 OPEN/PAUSED 会话可结台");
        }
        LocalDateTime now = LocalDateTime.now();
        if ("PAUSED".equals(po.getStatus()) && po.getPauseStartedAt() != null) {
            long elapsed = Math.max(0, java.time.Duration.between(po.getPauseStartedAt(), now).getSeconds());
            po.setPausedSeconds((po.getPausedSeconds() == null ? 0 : po.getPausedSeconds()) + Math.toIntExact(elapsed));
            po.setPauseStartedAt(null);
        }
        po.setStatus("CLOSED");
        po.setClosedAt(now);
        po.setUpdatedAt(now);
        applyOptimisticUpdate(po);

        OrderPo order = orderMapper.selectById(po.getOrderId());
        if (order != null) {
            // 结台：把同一行 ROOM_FEE 明细更新成最终金额（开台时已写过一遍，这里**不能新增第二行**，否则重复计费）。
            upsertRoomFeeItem(po, order, po.getClosedAt());
            // 包厢计时费刚落入明细，必须在本实例上重算订单金额后再写库，否则旧金额会覆盖新快照。
            orderAmounts.recalculate(order);
            if ("SERVING".equals(order.getStatus())) {
                order.setStatus("WAITING_SETTLEMENT");
            }
            order.setUpdatedAt(now);
            orderMapper.updateById(order);
        }
        // 高风险写操作（结台）审计：异步占位，失败仅告警不阻断结台（真实实现可改同步或 Outbox 兜底）。
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(po.getTenantId())
                .action("order.ktv_session.close")
                .actionLabel("结台")
                .resourceType("ktv_session")
                .resourceId(String.valueOf(sessionId))
                .idempotencyKey("ktv-session-close:" + sessionId)
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson("{\"orderId\":" + po.getOrderId() + ",\"status\":\"CLOSED\"}")
                .build());
        // 结台释放占用并进入「清洁中」：包厢本身不可再被开台/预约，直到门店确认清洁完成。
        if (resourceStateClient != null) {
            if (po.getOccupationId() != null) {
                resourceStateClient.release(po.getOccupationId());
            }
            resourceStateClient.setCleaning(po.getRoomResourceId(), true);
        }
        if (order != null) {
            autoSettleAfterClose(order);
        }
        return po;
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
    private void autoSettleAfterClose(OrderPo order) {
        if (settlementService == null || !"WAITING_SETTLEMENT".equals(order.getStatus())) {
            return;
        }
        if (orderAmounts != null && orderAmounts.hasPendingApprovalItems(order.getId())) {
            log.info("结台后仍有待确认的客户自助加项，保持待结算（处理后再结算）: orderId={}", order.getId());
            return;
        }
        // 版本列缺省 0（历史数据可能为空）：结算按 expectedVersion 做乐观锁，不能把 null 带进去。
        settlementService.settle(order.getId(), order.getVersion() == null ? 0 : order.getVersion());
    }

    /**
     * 写入/刷新订单的包厢计时费明细（ROOM_FEE，订单内唯一一行）。
     *
     * <p>计费口径 = 递增粒度（{@code increment_minutes}）+ 舍入方向（默认让利消费者），
     * 与开台中的实时预估（{@link #fillLiveEstimate}）共用 {@link #calculateRoomFee}，禁止两处各写一套。
     * 新口径下包厢费基数已含 1 名标准服务人员（{@link KtvPricingPlan#billableRoomPricePerIncrement()}），
     * 明细名称随之标注；历史快照（旧口径）保持「包厢计时费」原样。
     *
     * <p>三条路径共用本方法：**开台**（endAt=now，开台即计费）、**开台中刷新**（endAt=now，定时任务）、
     * **结台**（endAt=closed_at，最终金额）。已存在则更新同一行——多行会把房费重复计入订单合计。
     */
    private void upsertRoomFeeItem(KtvSessionPo session, OrderPo order, LocalDateTime endAt) {
        KtvPricingPlan plan = snapshotPlan(session.getBillingRuleSnapshotJson(), session.getTenantId(), order.getStoreId());
        if (!KtvRoomFeeCalculator.durationBillable(plan)) {
            // PACKAGE 为固定时长固定价套餐，特判逻辑后续接入 pricing 服务实现。
            return;
        }
        KtvRoomFeeCalculator.Fee fee = calculateRoomFee(session, plan, endAt);

        LocalDateTime now = LocalDateTime.now();
        OrderItemPo existing = findRoomFeeItem(session.getOrderId());
        OrderItemPo item = existing == null ? new OrderItemPo() : existing;
        if (existing == null) {
            item.setTenantId(session.getTenantId());
            item.setOrderId(session.getOrderId());
            item.setItemType("ROOM_FEE");
            item.setStatus("ACTIVE");
            item.setDiscountAmount(BigDecimal.ZERO);
            item.setTaxAmount(BigDecimal.ZERO);
            item.setCreatedAt(now);
        }
        item.setNameSnapshot(roomFeeItemName(plan));
        item.setUnitPrice(BigDecimal.valueOf(fee.unitPriceMinor()));
        item.setQuantity(BigDecimal.valueOf(fee.units()));
        item.setTotalAmount(BigDecimal.valueOf(fee.amountMinor()));
        // 币种快照（16_CURRENCY_CONVENTIONS §5）：房费明细跟随其订单的币种快照。
        item.setCurrencyCode(Currency.parse(order.getCurrencyCode()).code());
        item.setPriceSnapshotJson(plan.toSnapshotJson());
        item.setUpdatedAt(now);
        if (existing == null) {
            orderItemMapper.insert(item);
        } else {
            orderItemMapper.updateById(item);
        }
    }

    /** 订单已有的房费明细（订单内唯一一行；历史脏数据取最早一行，后续更新都落在它上面）。 */
    private OrderItemPo findRoomFeeItem(Long orderId) {
        QueryWrapper<OrderItemPo> query = new QueryWrapper<>();
        query.eq("order_id", orderId).eq("item_type", "ROOM_FEE").orderByAsc("id").last("LIMIT 1");
        List<OrderItemPo> items = orderItemMapper.selectList(query);
        return items == null || items.isEmpty() ? null : items.get(0);
    }

    /**
     * 批量取多个订单「已生效（ACTIVE）房费明细金额」的合计（最小货币单位；没有房费明细的订单不在结果里）。
     *
     * <p>为什么需要批量：订单列表/详情投影一次要处理整页订单，逐单调用 {@link #findRoomFeeItem(Long)}
     * 会变成 N+1。这里走 {@link OrderItemMapper#selectActiveRoomFeeItemsByOrderIds} 一次查完。
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
        List<OrderItemPo> items = orderItemMapper.selectActiveRoomFeeItemsByOrderIds(orderIds);
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> amounts = new java.util.HashMap<>();
        for (OrderItemPo item : items) {
            if (item.getOrderId() != null) {
                amounts.merge(item.getOrderId(), minorOf(item.getTotalAmount()), Long::sum);
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
     * 批量取多个订单的「已生效明细合计 + 其中房费明细合计」：订单投影一次查询拿齐两个数
     * （差额法要扣房费快照，回退基数要全明细合计），逐单查就是 N+1。
     *
     * <p>为什么返回明细行而不是 SQL 聚合值：与 {@link OrderItemMapper#selectActiveRoomFeeItemsByOrderIds}
     * 同款取舍——金额列是 decimal，回读后在 Java 侧按账单同一个「BigDecimal → 最小货币单位 long」
     * 收敛，避免 SQL 聚合别名在 MySQL / 测试 H2 之间大小写不一致。行口径与账单/订单重算完全一致：
     * 只统计 {@code status=ACTIVE} 的明细（待确认/已拒绝不计入）。
     */
    public Map<Long, OrderAmountSums> orderAmountSumsByOrderIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            // 空集合不能下推（会拼出非法的 IN ()），直接返回空；调用方按「没有明细」处理。
            return Map.of();
        }
        List<OrderItemPo> items = orderItemMapper.selectActiveItemsByOrderIds(orderIds);
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        Map<Long, OrderAmountSums> sums = new java.util.HashMap<>();
        for (OrderItemPo item : items) {
            if (item.getOrderId() == null) {
                continue;
            }
            long amount = minorOf(item.getTotalAmount());
            boolean roomFee = "ROOM_FEE".equals(item.getItemType());
            sums.merge(item.getOrderId(), new OrderAmountSums(amount, roomFee ? amount : 0L),
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
     * （{@link #fillLiveEstimate} 与 {@code BillApplicationService} 对 OPEN 会话按「此刻结台」计算），
     * 本方法负责让**库里**的 ROOM_FEE 明细与订单合计也跟上时间——既修复本轮之前开台、明细缺失的存量订单，
     * 也避免长开台订单的合计长期停在旧值。会话与订单状态机仍由各入口维护，这里只碰金额字段。
     *
     * <p>截止时刻走 {@link #billableEndAt}：PAUSED 会话停表在 pause_started_at，与看板实时值、
     * 账单实时值、结台固化四处同一口径（挂单期间库内房费也跟着停表，不再「暂停还在长钱」）。
     */
    @Transactional
    public int refreshOpenRoomFees() {
        List<KtvSessionPo> sessions = sessionMapper.selectOpenSessions();
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int refreshed = 0;
        for (KtvSessionPo session : sessions) {
            // 定时任务线程没有租户上下文，而订单/明细的 mapper 走 TenantLineInnerInterceptor（缺上下文直接抛）。
            // 这里按会话自带 tenantId 逐个设置上下文：既满足拦截器，也天然限制「只能写这个租户的行」。
            TenantContext previousContext = TenantContextHolder.get();
            try {
                TenantContextHolder.set(new TenantContext(session.getTenantId(), null, null, 0L, 0));
                OrderPo order = orderMapper.selectById(session.getOrderId());
                if (order == null || !"SERVING".equals(order.getStatus())) {
                    // 会话残留但订单已终结（取消/作废）：交回状态机处理，这里不动金额。
                    continue;
                }
                upsertRoomFeeItem(session, order, billableEndAt(session, now));
                orderAmounts.recalculate(order);
                order.setUpdatedAt(now);
                orderMapper.updateById(order);
                refreshed++;
            } catch (RuntimeException failure) {
                log.warn("刷新开台中包厢房费失败（下一轮重试）: sessionId={}, cause={}",
                        session.getId(), failure.getMessage());
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
    static KtvRoomFeeCalculator.Fee calculateRoomFee(KtvSessionPo session, KtvPricingPlan plan, LocalDateTime endAt) {
        int paused = session.getPausedSeconds() == null ? 0 : session.getPausedSeconds();
        long billable = KtvRoomFeeCalculator.billableSeconds(session.getBillingStartAt(), endAt, paused);
        long standard = KtvRoomFeeCalculator.standardSeconds(session.getBillingStartAt(), session.getReservedEndAt(),
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
    public static LocalDateTime billableEndAt(KtvSessionPo session, LocalDateTime now) {
        if (session != null && "PAUSED".equals(session.getStatus()) && session.getPauseStartedAt() != null) {
            return session.getPauseStartedAt();
        }
        return now;
    }

    /**
     * 开台中会话的**实时房费**（唯一实现：看板实时估算与账单实时值共用）。
     * 计划不按时长计费（PACKAGE 一口价）时返回 {@code null}：此时没有房费明细、也不产生实时房费，
     * 调用方一律按「合计即库内值」处理，绝不凭空造一条 0 元房费行。
     */
    static KtvRoomFeeCalculator.Fee liveRoomFee(KtvSessionPo session, KtvPricingPlan plan, LocalDateTime now) {
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
    public KtvSessionPo transfer(Long sessionId, Long newResourceId) {
        try {
            KtvSessionPo po = require(sessionId);
            if (!"OPEN".equals(po.getStatus()) && !"PAUSED".equals(po.getStatus())) {
                throw new BusinessException("ORDER_STATUS_INVALID", "仅 OPEN/PAUSED 会话可转台");
            }
            if (newResourceId == null) {
                throw new ApiException(400, "AMOUNT_INVALID", "目标包厢不能为空");
            }
            Long previousOccupationId = po.getOccupationId();
            po.setRoomResourceId(newResourceId);
            applyRoomSnapshot(po);
            po.setUpdatedAt(LocalDateTime.now());
            if (sessionMapper.transferRoom(po) == 0) {
                throw new BusinessException("SESSION_VERSION_CONFLICT", "KTV 会话已被并发修改，请重试");
            }
            if (resourceStateClient != null) {
                if (previousOccupationId != null) {
                    resourceStateClient.release(previousOccupationId);
                }
                LocalDateTime now = LocalDateTime.now();
                resourceStateClient.occupy(newResourceId, "ORDER", po.getOrderId(), now, now.plusHours(24))
                        .ifPresent(occupationId -> {
                            po.setOccupationId(occupationId);
                            sessionMapper.updateById(po);
                        });
            }
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action("order.transfer")
                    .actionLabel("订单转台")
                    .resourceType("ktv_session").resourceId(String.valueOf(sessionId))
                    .resourceName(po.getRoomNameSnapshot())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"orderId\":" + po.getOrderId() + ",\"previousOccupationId\":" + previousOccupationId
                            + ",\"targetResourceId\":" + newResourceId + "}")
                    .build());
            return po;
        } catch (RuntimeException failure) {
            // 转台失败留痕：换包厢失败会留下「订单还在旧包厢/占用未切换」的中间态，必须可回溯。
            recordFailure("order.transfer", sessionId, failure);
            throw failure;
        }
    }

    /** 暂停修正：店长/财务修正 paused_seconds（KTV_BUSINESS_01 §11.5，写审计）。 */
    @Transactional
    public KtvSessionPo correctPause(Long sessionId, Integer correctedPausedSeconds) {
        try {
            KtvSessionPo po = require(sessionId);
            if (!"OPEN".equals(po.getStatus()) && !"PAUSED".equals(po.getStatus())) {
                throw new BusinessException("ORDER_STATUS_INVALID", "仅 OPEN/PAUSED 会话可修正暂停");
            }
            if (correctedPausedSeconds == null || correctedPausedSeconds < 0) {
                throw new ApiException(400, "AMOUNT_INVALID", "修正后的暂停秒数不能为负");
            }
            po.setPausedSeconds(correctedPausedSeconds);
            po.setUpdatedAt(LocalDateTime.now());
            applyOptimisticUpdate(po);
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(po.getTenantId())
                    .action("order.ktv_session.correct_pause")
                    .actionLabel("暂停时长修正")
                    .resourceType("ktv_session")
                    .resourceId(String.valueOf(sessionId))
                    .idempotencyKey("ktv-session-correct-pause:" + sessionId)
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"orderId\":" + po.getOrderId() + ",\"pausedSeconds\":" + correctedPausedSeconds + "}")
                    .build());
            return po;
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
    private void recordSessionAudit(String action, String actionLabel, KtvSessionPo po, String detailJson) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(po.getTenantId())
                .action(action)
                .actionLabel(actionLabel)
                .resourceType("ktv_session").resourceId(String.valueOf(po.getId()))
                .resourceName(po.getRoomNameSnapshot())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson(detailJson)
                .build());
    }

    /** 按订单 ID 批量查会话（订单列表一次查询带出包厢信息，避免 N+1）。 */
    public Map<Long, KtvSessionPo> listByOrderIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        return sessionMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KtvSessionPo>()
                        .in(KtvSessionPo::getOrderId, orderIds))
                .stream()
                .collect(java.util.stream.Collectors.toMap(KtvSessionPo::getOrderId, session -> session,
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
    public static KtvSessionPo currentSessionOf(KtvSessionPo first, KtvSessionPo second) {
        int rankFirst = sessionCurrentRank(first);
        int rankSecond = sessionCurrentRank(second);
        if (rankFirst != rankSecond) {
            return rankSecond > rankFirst ? second : first;
        }
        long idFirst = first == null || first.getId() == null ? Long.MIN_VALUE : first.getId();
        long idSecond = second == null || second.getId() == null ? Long.MIN_VALUE : second.getId();
        return idSecond > idFirst ? second : first;
    }

    /** 「当前」程度排序：进行中 2 > 待开台 1 > 已结束（CLOSED/CANCELLED）0。 */
    private static int sessionCurrentRank(KtvSessionPo session) {
        if (session == null || session.getStatus() == null) {
            return Integer.MIN_VALUE;
        }
        return switch (session.getStatus()) {
            case "OPEN", "PAUSED" -> 2;
            case "RESERVED" -> 1;
            default -> 0;
        };
    }

    /**
     * 从订单的**全部**会话里挑出「当前这一次」：与 {@link #currentSessionOf(KtvSessionPo, KtvSessionPo)}
     * 同一条规则（见其说明），供账单这类一次只能拿到 List 的调用方复用，避免两处各写一套挑选逻辑。
     */
    public static KtvSessionPo currentSessionOf(List<KtvSessionPo> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return null;
        }
        KtvSessionPo current = null;
        for (KtvSessionPo session : sessions) {
            current = current == null ? session : currentSessionOf(current, session);
        }
        return current;
    }

    /** 按订单 ID 查会话（转台/作废等按订单命令驱动）；开台中附带实时计时与预估计时费。 */
    public KtvSessionPo findByOrderId(Long orderId) {
        KtvSessionPo po = sessionMapper.selectByOrderId(orderId);
        if (po == null) {
            throw new BusinessException("ORDER_NOT_FOUND", "订单对应的 KTV 会话不存在");
        }
        fillLiveEstimate(po, null);
        return po;
    }

    /**
     * 开台中的包厢计时费实时估算（订单列表/会话详情展示「包厢计时中 · 预估 ¥X」）。
     * 结台后金额以固化明细为准，不再覆盖。storeId 为空时从订单读取。
     *
     * <p>暂停中（PAUSED）**停表**：截止时刻取 pause_started_at，与结台固化口径一致（见
     * {@link #billableEndAt}）；否则取当前时刻。金额由 {@link #liveRoomFee} 唯一算出，
     * 账单实时值、定时刷新与看板三处共用同一条公式。
     */
    public void fillLiveEstimate(KtvSessionPo po, Long storeId) {
        if (po == null || !"OPEN".equals(po.getStatus()) && !"PAUSED".equals(po.getStatus())) {
            return;
        }
        Long effectiveStoreId = storeId;
        if (effectiveStoreId == null) {
            OrderPo order = po.getOrderId() == null ? null : orderMapper.selectById(po.getOrderId());
            effectiveStoreId = order == null ? null : order.getStoreId();
        }
        KtvPricingPlan plan = snapshotPlan(po.getBillingRuleSnapshotJson(), po.getTenantId(), effectiveStoreId);
        // 与结台写库同一条计算路径：预估金额必须等于「此刻结台」的 ROOM_FEE 金额。
        KtvRoomFeeCalculator.Fee fee = liveRoomFee(po, plan, LocalDateTime.now());
        if (fee == null) {
            return;
        }
        po.setElapsedSeconds(fee.billableSeconds());
        po.setEstimatedRoomFee(fee.amountMinor());
    }

    /** 乐观锁提交：version 不匹配（受影响 0 行）时抛并发冲突。 */
    private void applyOptimisticUpdate(KtvSessionPo po) {
        if (sessionMapper.updateWithVersion(po) == 0) {
            throw new BusinessException("SESSION_VERSION_CONFLICT", "KTV 会话已被并发修改，请重试");
        }
        po.setVersion((po.getVersion() == null ? 0 : po.getVersion()) + 1);
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
                    com.gvchat.platform.order.domain.ktv.model.KtvBillingUnit.fromCode(node.path("billingUnit").asText(null)),
                    node.path("roomUnitPrice").asLong(), node.path("defaultSessionMinutes").asInt(),
                    node.path("freeWaitMinutes").asInt(), new BigDecimal(node.path("overtimeRate").asText("1.0")),
                    node.path("incrementMinutes").asInt(30),
                    com.gvchat.platform.order.domain.ktv.model.KtvRoundingDirection.fromCode(node.path("roundingDirection").asText(null)),
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
    private static java.util.Map<String, Long> unitPriceByRoomType(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, Long> prices = new java.util.LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            long price = entry.getValue().asLong(0L);
            if (price > 0) {
                prices.put(entry.getKey(), price);
            }
        });
        return prices;
    }

    /** 取字符串字段：字段缺失或 JSON null 都返回 null（Jackson 的 NullNode.asText() 会给出字面量 "null"）。 */
    private static String textOrNull(com.fasterxml.jackson.databind.JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private KtvSessionPo require(Long sessionId) {
        KtvSessionPo po = sessionMapper.selectById(sessionId);
        if (po == null) {
            if (sessionMapper.selectTenantIdById(sessionId) != null) {
                throw new ApiException(403, "TENANT_SCOPE_DENIED", "无权访问其他租户的会话");
            }
            throw new BusinessException("ORDER_NOT_FOUND", "KTV 会话不存在");
        }
        return po;
    }
}
