package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.currency.Currency;
import io.openware.platform.order.domain.ktv.model.KtvPricingPlan;
import io.openware.platform.order.domain.ktv.model.KtvRoundingDirection;
import io.openware.platform.order.domain.ktv.model.KtvServerSession;
import io.openware.platform.order.domain.ktv.model.KtvServerSessionStatus;
import io.openware.platform.order.domain.ktv.port.KtvPricingPlanProvider;
import io.openware.platform.order.domain.ktv.repository.KtvServerSessionRepository;
import io.openware.platform.order.domain.ktv.repository.KtvSessionRepository;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.OrderItem;
import io.openware.platform.order.domain.order.repository.OrderItemRepository;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.infra.client.ResourceStateClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 服务人员点单（状态机 ORDERED → SERVING → ENDED / CANCELLED，KTV_BUSINESS_01 §5）。
 * 计费按分钟整数运算：每递增粒度单价 × 块数，向下抹零（默认 CONSUMER_FAVOR 让利），无浮点小数。
 *
 * <p>点服务人员时把「谁在服务本包厢」固化进 KTV 会话（ord_ktv_session.server_id/server_name）：
 * 订单列表与房态看板直接读会话快照，不再跨域回查点单表；资源服务不可达时只写 ID、保留已有名称。
 *
 * <p><b>持久化边界</b>：本服务只依赖领域端口（{@link KtvServerSessionRepository} /
 * {@link KtvSessionRepository}）与领域模型，不再接触 MyBatis Mapper 或持久化对象。
 * 服务人员点单的并发保护仍是端口里的 {@code UPDATE ... WHERE id = ? AND version = ?}
 * （{@code ord_ktv_server_session} 没有 MyBatis-Plus {@code @Version}，工程里也没有乐观锁拦截器），
 * 本服务只负责把「受影响 0 行」转成明确的 {@code SESSION_VERSION_CONFLICT}。
 */
@Service
public class KtvServerSessionApplicationService {
    private final KtvServerSessionRepository serverSessionRepository;
    private final OrderRepository orderRepository;
    private final KtvPricingPlanProvider pricingPlanProvider;
    private final AuditClient auditClient;
    private final OrderItemRepository orderItemRepository;
    private final KtvSessionRepository ktvSessionRepository;
    private final ResourceStateClient resourceStateClient;
    /** 服务人员费明细落库后必须同步订单金额快照（与房费同款处理）；测试装配可为 null（跳过）。 */
    private final OrderAmountApplicationService orderAmounts;

    /** 兼容既有装配/单元测试：无会话回写依赖时跳过服务人员快照回写。 */
    public KtvServerSessionApplicationService(KtvServerSessionRepository serverSessionRepository,
                                              OrderRepository orderRepository,
                                              KtvPricingPlanProvider pricingPlanProvider,
                                              AuditClient auditClient,
                                              OrderItemRepository orderItemRepository) {
        this(serverSessionRepository, orderRepository, pricingPlanProvider, auditClient, orderItemRepository, null,
                null, null);
    }

    /** 兼容既有装配/单元测试：有会话回写依赖，但不做订单金额重算（金额重算缺省跳过）。 */
    public KtvServerSessionApplicationService(KtvServerSessionRepository serverSessionRepository,
                                              OrderRepository orderRepository,
                                              KtvPricingPlanProvider pricingPlanProvider,
                                              AuditClient auditClient,
                                              OrderItemRepository orderItemRepository,
                                              KtvSessionRepository ktvSessionRepository,
                                              ResourceStateClient resourceStateClient) {
        this(serverSessionRepository, orderRepository, pricingPlanProvider, auditClient, orderItemRepository,
                ktvSessionRepository, resourceStateClient, null);
    }

    @Autowired
    public KtvServerSessionApplicationService(KtvServerSessionRepository serverSessionRepository,
                                              OrderRepository orderRepository,
                                              KtvPricingPlanProvider pricingPlanProvider,
                                              AuditClient auditClient,
                                              OrderItemRepository orderItemRepository,
                                              KtvSessionRepository ktvSessionRepository,
                                              ResourceStateClient resourceStateClient,
                                              OrderAmountApplicationService orderAmounts) {
        this.serverSessionRepository = serverSessionRepository;
        this.orderRepository = orderRepository;
        this.pricingPlanProvider = pricingPlanProvider;
        this.auditClient = auditClient;
        this.orderItemRepository = orderItemRepository;
        this.ktvSessionRepository = ktvSessionRepository;
        this.resourceStateClient = resourceStateClient;
        this.orderAmounts = orderAmounts;
    }

    /** 点服务人员：POST /business/orders/{id}/servers → ORDERED（计时开始）。 */
    @Transactional
    public KtvServerSession order(Long tenantId, Long orderId, Long ktvSessionId,
                                  Long serverResourceId, Long catalogItemId) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
            }
            if (!"SERVING".equals(order.status())) {
                throw new BusinessException("ORDER_STATUS_INVALID", "仅服务中订单可点服务人员");
            }
            KtvPricingPlan plan = pricingPlanProvider.resolve(tenantId, order.storeId());
            LocalDateTime now = LocalDateTime.now();
            // 币种快照（16_CURRENCY_CONVENTIONS §5）：服务人员费继承所属订单的币种快照。
            KtvServerSession session = KtvServerSession.ordered(tenantId, order.storeId(),
                    order.businessType(), orderId, ktvSessionId, serverResourceId, catalogItemId, now, plan,
                    Currency.parse(order.currencyCode()).code());
            // 审计四列（created_at/updated_at）由适配器在落库时填充：记账信息不进领域模型。
            session = serverSessionRepository.insert(session);
            // 点单即为「本包厢的服务人员」：把服务人员快照写入 KTV 会话，订单列表/房态看板直接读。
            writeServerSnapshotToSession(ktvSessionId, serverResourceId);
            // 点服务人员即开始计费，此前成功/失败都没有留痕。
            recordSessionAudit("order.ktv_server_session.order", "点服务人员", session,
                    "{\"orderId\":" + orderId + ",\"status\":\"ORDERED\",\"serverResourceId\":"
                            + serverResourceId + "}");
            return session;
        } catch (RuntimeException failure) {
            recordFailure("order.ktv_server_session.order", orderId, null, failure);
            throw failure;
        }
    }

    /** 开始服务：ORDERED → SERVING（内部计时，无对外端点，KTV_BUSINESS_01 §5.2）。 */
    @Transactional
    public KtvServerSession start(Long sessionId) {
        KtvServerSession session = require(sessionId);
        if (!KtvServerSessionStatus.ORDERED.name().equals(session.status())) {
            throw new BusinessException("ORDER_STATUS_INVALID", "仅 ORDERED 点单可开始服务");
        }
        LocalDateTime now = LocalDateTime.now();
        session = applyOptimisticUpdate(session
                .withStatus(KtvServerSessionStatus.SERVING.name())
                .withStartedAt(now));
        // 开始服务时再固化一次快照：点单接口可能没带 ktvSessionId，或期间换了服务人员。
        writeServerSnapshotToSession(session.ktvSessionId(), session.serverResourceId());
        return session;
    }

    /**
     * 把服务人员快照写入 KTV 会话（ord_ktv_session.server_id/server_name）。
     *
     * <p>名称来自资源域（KTV_SERVER 资源名）；资源服务不可达时只写 ID，SQL 用 COALESCE 保留既有名称，
     * 避免一次外部抖动把已固化的服务人员名清空。缺会话/缺服务人员时跳过（不阻断点单）。
     * 审计列 {@code updated_at} 由适配器在同一条语句里刷新。
     */
    private void writeServerSnapshotToSession(Long ktvSessionId, Long serverResourceId) {
        if (ktvSessionRepository == null || ktvSessionId == null || serverResourceId == null) {
            return;
        }
        String serverName = resourceStateClient == null
                ? null
                : resourceStateClient.room(serverResourceId).map(ResourceStateClient.RoomSnapshot::name).orElse(null);
        ktvSessionRepository.updateServerSnapshot(ktvSessionId, serverResourceId, serverName);
    }

    /** 结束服务：POST /business/ktv/servers/{id}/end → ENDED，固化时长/金额。 */
    @Transactional
    public KtvServerSession end(Long sessionId) {
        try {
            return doEnd(sessionId);
        } catch (RuntimeException failure) {
            // 结束服务失败留痕（状态冲突/并发冲突/落库失败）：该动作直接固化计费金额。
            recordFailure("order.ktv_server_session.end", null, sessionId, failure);
            throw failure;
        }
    }

    private KtvServerSession doEnd(Long sessionId) {
        KtvServerSession session = require(sessionId);
        String status = session.status();
        if (!KtvServerSessionStatus.ORDERED.name().equals(status)
                && !KtvServerSessionStatus.SERVING.name().equals(status)) {
            throw new BusinessException("ORDER_STATUS_INVALID", "仅 ORDERED/SERVING 点单可结束服务");
        }
        LocalDateTime endedAt = LocalDateTime.now();
        LocalDateTime start = session.startedAt() != null ? session.startedAt() : session.orderedAt();
        long sec = start == null ? 0L : Duration.between(start, endedAt).getSeconds();
        KtvRoundingDirection direction = KtvRoundingDirection.fromCode(session.roundingDirection());
        // M 与 n 走与包厢房费同一套舍入算法（默认让利）：33 秒 → M=0 → n=0 → 0 元，不会按整块多收。
        int minutes = UnitTimeFeeCalculator.calculateMinutes(sec, direction);
        long blocks = UnitTimeFeeCalculator.calculateBlocks(sec, session.incrementMinutes(), direction);
        long billedMinor = UnitTimeFeeCalculator.calculateTotal(sec, session.incrementMinutes(),
                session.pricePerInc(), direction);
        // 包厢费（roomFeeIncludesServer）已含 1 名标准服务人员：同一包厢会话下**按创建顺序第一个未取消**的
        // 服务人员会话占用免费名额，不另计费；第 2 名起按各自会话的 price_per_inc 全价计。
        boolean freeQuota = occupiesFreeServerQuota(session);
        long totalMinor = freeQuota ? 0L : billedMinor;
        // 币种快照兜底：点单时已写入；历史/异常行为空时按默认 USD 归一，绝不让快照为空。
        session = applyOptimisticUpdate(session
                .withEndedAt(endedAt)
                .withDurationSeconds((int) sec)
                .withDurationMinutes(minutes)
                .withTotalAmount(BigDecimal.valueOf(totalMinor))
                .withCurrencyCode(Currency.parse(session.currencyCode()).code())
                .withStatus(KtvServerSessionStatus.ENDED.name()));
        OrderItem item = new OrderItem(
                null,
                session.tenantId(),
                session.storeId(),
                session.businessType(),
                session.orderId(),
                "SERVICE",
                session.catalogItemId(),
                session.serverResourceId(),
                serverFeeItemName(session, freeQuota),
                // 免费名额：单价 0（金额 0），明细照写 —— servers 分区必须完整可解释，不能凭空少一名服务人员。
                BigDecimal.valueOf(freeQuota || session.pricePerInc() == null ? 0L : session.pricePerInc()),
                // 明细数量 = 计费块数（与金额同源），保证「单价 × 数量 = 金额」与账单/订单合计口径一致。
                BigDecimal.valueOf(blocks),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                session.totalAmount(),
                // 服务人员费明细与 ord_ktv_server_session 用同一币种快照，账单两侧口径一致。
                session.currencyCode(),
                // 快照固化「本轮是否占用免费名额」，与 rooms 分区/账单展示同源，事后可复算免了几名。
                session.priceSnapshotJson() == null
                        ? null
                        : withFreeServerQuota(session.priceSnapshotJson(), freeQuota),
                OrderItem.STATUS_ACTIVE,
                OrderItem.SOURCE_MERCHANT,
                null,
                null,
                null,
                null,
                null,
                null);
        orderItemRepository.insert(item);
        // 服务人员费刚落入明细，必须同步重算订单金额快照（与结台写房费同一款处理）。
        // 缺了这一步：库内 total_amount 不含服务人员费，账单会出现「服务人员行有钱、合计对不上」——
        // 即 sum(items)+sum(servers)+roomFee != totalAmount，且要等到下一次结算/刷新才补上（中间一直少收）。
        recalculateOrderAmounts(session.orderId());
        // 高风险写操作（结束服务并固化金额）审计：异步占位。
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(session.tenantId())
                .action("order.ktv_server_session.end")
                .actionLabel("服务人员结束服务")
                .resourceType("ktv_server_session")
                .resourceId(String.valueOf(sessionId))
                .idempotencyKey("ktv-server-end:" + sessionId)
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson("{\"orderId\":" + session.orderId() + ",\"totalMinor\":" + totalMinor
                        + ",\"freeServerQuota\":" + freeQuota + "}")
                .build());
        return session;
    }

    /** 服务人员明细名：免费名额标注「不另计费」，第 2 名起为「额外服务人员」。 */
    static String serverFeeItemName(KtvServerSession session, boolean freeQuota) {
        return freeQuota
                ? "服务人员#" + session.serverResourceId() + "（含 1 名标准服务人员，不另计费）"
                : "额外服务人员#" + session.serverResourceId();
    }

    /**
     * 服务人员费落库后同步订单金额快照：读订单 → 在本实例上重算 → 一次写库（与结台写房费同一款顺序，
     * 避免旧实例覆盖新金额）。订单不存在或未装配金额服务（单测/降级装配）时跳过，绝不阻断结束服务。
     */
    private void recalculateOrderAmounts(Long orderId) {
        if (orderAmounts == null || orderId == null) {
            return;
        }
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return;
        }
        order = orderAmounts.recalculate(order);
        // 条件更新：同一订单上的两个服务人员会话可以各自通过自己的会话 CAS，
        // 随后两次重算都会写订单——无条件写回会让后写者覆盖前者算出的服务人员费。
        int expectedVersion = order.currentVersion();
        if (!orderRepository.updateAmountsAndStatusWithVersion(order, expectedVersion)) {
            throw new BusinessException("ORDER_VERSION_CONFLICT", "订单金额已被并发修改，请重试");
        }
    }

    /**
     * 本轮是否占用免费名额：同一 KTV 会话（缺 ktvSessionId 时退化为同一订单）下，
     * 按创建顺序（id 升序）排除已取消后的**第一个**服务人员会话即免费名额。
     *
     * <p>只依赖「创建顺序 + 状态」，与 end 调用顺序/并发无关：最早会话被取消时名额顺延给下一个未取消会话，
     * 任意时刻最多只有一个免费名额。查不到同类会话（数据缺失）时按不免费处理，避免静默漏收。
     */
    private boolean occupiesFreeServerQuota(KtvServerSession session) {
        if (serverSessionRepository == null) {
            return false;
        }
        List<KtvServerSession> sameRoom = session.ktvSessionId() != null
                ? serverSessionRepository.listByKtvSessionId(session.ktvSessionId())
                : serverSessionRepository.listByOrderId(session.orderId());
        return freeServerSessionIds(sameRoom).contains(session.id());
    }

    /**
     * 免费名额服务人员会话 id 集合：按包厢会话（缺 ktvSessionId 时按订单）分组，每组取
     * 「创建顺序最早且未取消」的一个。账单（{@code BillApplicationService}）与本服务共用本方法，
     * 保证「免费标注」与「免费计费」永远同一判定。
     */
    public static Set<Long> freeServerSessionIds(List<KtvServerSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return Set.of();
        }
        Map<Object, Long> freeByGroup = new HashMap<>();
        for (KtvServerSession s : sessions) {
            if (s == null || s.id() == null) {
                continue;
            }
            if (KtvServerSessionStatus.CANCELLED.name().equals(s.status())) {
                continue;
            }
            Object group = s.ktvSessionId() != null ? s.ktvSessionId() : ("order:" + s.orderId());
            Long current = freeByGroup.get(group);
            if (current == null || s.id() < current) {
                freeByGroup.put(group, s.id());
            }
        }
        return new HashSet<>(freeByGroup.values());
    }

    /** 在已固化的计价快照上补 freeServerQuota 标记（幂等：已有该字段时不重复追加）。 */
    static String withFreeServerQuota(String snapshotJson, boolean freeQuota) {
        if (snapshotJson == null || snapshotJson.isBlank() || !snapshotJson.endsWith("}")) {
            return snapshotJson;
        }
        if (snapshotJson.contains("\"freeServerQuota\"")) {
            return snapshotJson;
        }
        return snapshotJson.substring(0, snapshotJson.length() - 1) + ",\"freeServerQuota\":" + freeQuota + "}";
    }

    /** 取消点单：POST /business/ktv/servers/{id}/cancel → CANCELLED（仅 ORDERED 且未计费）。 */
    @Transactional
    public KtvServerSession cancel(Long sessionId) {
        try {
            KtvServerSession session = require(sessionId);
            if (!KtvServerSessionStatus.ORDERED.name().equals(session.status())) {
                throw new BusinessException("ORDER_STATUS_INVALID", "仅未开始计费的 ORDERED 点单可取消");
            }
            session = applyOptimisticUpdate(session.withStatus(KtvServerSessionStatus.CANCELLED.name()));
            // 取消点单会影响「免费服务人员名额」的顺延结果，此前成功/失败都没有留痕。
            recordSessionAudit("order.ktv_server_session.cancel", "服务人员点单取消", session,
                    "{\"orderId\":" + session.orderId() + ",\"status\":\"CANCELLED\"}");
            return session;
        } catch (RuntimeException failure) {
            recordFailure("order.ktv_server_session.cancel", null, sessionId, failure);
            throw failure;
        }
    }

    /**
     * 服务人员点单写操作失败留痕：动作码与成功路径同码，{@code result=FAILURE} + 稳定 errorCode。
     *
     * <p>审计只走 {@link AuditClient#recordAsync}（失败仅 WARN），业务异常原样抛出：留痕不改变业务结果。
     * 不带幂等键（重复失败各自留痕）；detail 只留定位 ID，不含金额与服务人员姓名。
     */
    private void recordFailure(String action, Long tenantId, Long sessionId, RuntimeException failure) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(tenantId)
                .action(action)
                .resourceType("ktv_server_session")
                .resourceId(sessionId == null ? null : String.valueOf(sessionId))
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"serverSessionId\":" + sessionId + "}")
                .build());
    }

    /** 点单/取消的成功留痕（欠补齐动作）：与失败同码，detail 只放检索字段。 */
    private void recordSessionAudit(String action, String actionLabel, KtvServerSession session,
                                    String detailJson) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(session.tenantId())
                .action(action)
                .actionLabel(actionLabel)
                .resourceType("ktv_server_session").resourceId(String.valueOf(session.id()))
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson(detailJson)
                .build());
    }

    /**
     * 乐观锁提交：version 不匹配（受影响 0 行）时抛并发冲突。
     *
     * <p>与包厢会话不同，这里**不推进内存版本号**：迁移前就是如此（返回的对象仍带读库时的版本），
     * 保持原行为，不借迁移顺手改返回契约。
     */
    private KtvServerSession applyOptimisticUpdate(KtvServerSession session) {
        if (!serverSessionRepository.updateWithVersion(session)) {
            throw new BusinessException("SESSION_VERSION_CONFLICT", "服务人员点单已被并发修改，请重试");
        }
        return session;
    }

    private KtvServerSession require(Long sessionId) {
        Optional<KtvServerSession> session = serverSessionRepository.findById(sessionId);
        if (session.isEmpty()) {
            if (serverSessionRepository.findTenantIdById(sessionId) != null) {
                throw new ApiException(403, "TENANT_SCOPE_DENIED", "无权访问其他租户的服务人员点单");
            }
            throw new BusinessException("ORDER_NOT_FOUND", "服务人员点单不存在");
        }
        return session.get();
    }
}
