package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.currency.Currency;
import io.openware.platform.order.domain.ktv.model.KtvPricingPlan;
import io.openware.platform.order.domain.ktv.model.KtvRoundingDirection;
import io.openware.platform.order.domain.ktv.model.KtvServerSessionStatus;
import io.openware.platform.order.domain.ktv.port.KtvPricingPlanProvider;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.infra.persistence.mapper.KtvServerSessionMapper;
import io.openware.platform.order.infra.persistence.mapper.KtvSessionMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderItemMapper;
import io.openware.platform.order.infra.persistence.po.KtvServerSessionPo;
import io.openware.platform.order.infra.persistence.po.OrderItemPo;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务人员点单（状态机 ORDERED → SERVING → ENDED / CANCELLED，KTV_BUSINESS_01 §5）。
 * 计费按分钟整数运算：每递增粒度单价 × 块数，向下抹零（默认 CONSUMER_FAVOR 让利），无浮点小数。
 *
 * <p>点服务人员时把「谁在服务本包厢」固化进 KTV 会话（ord_ktv_session.server_id/server_name）：
 * 订单列表与房态看板直接读会话快照，不再跨域回查点单表；资源服务不可达时只写 ID、保留已有名称。
 */
@Service
public class KtvServerSessionApplicationService {
    private final KtvServerSessionMapper serverSessionMapper;
    private final OrderMapper orderMapper;
    private final KtvPricingPlanProvider pricingPlanProvider;
    private final AuditClient auditClient;
    private final OrderItemMapper orderItemMapper;
    private final KtvSessionMapper ktvSessionMapper;
    private final ResourceStateClient resourceStateClient;
    /** 服务人员费明细落库后必须同步订单金额快照（与房费同款处理）；测试装配可为 null（跳过）。 */
    private final OrderAmountApplicationService orderAmounts;

    /** 兼容既有装配/单元测试：无会话回写依赖时跳过服务人员快照回写。 */
    public KtvServerSessionApplicationService(KtvServerSessionMapper serverSessionMapper,
                                              OrderMapper orderMapper,
                                              KtvPricingPlanProvider pricingPlanProvider,
                                              AuditClient auditClient,
                                              OrderItemMapper orderItemMapper) {
        this(serverSessionMapper, orderMapper, pricingPlanProvider, auditClient, orderItemMapper, null, null, null);
    }

    /** 兼容既有装配/单元测试：有会话回写依赖，但不做订单金额重算（金额重算缺省跳过）。 */
    public KtvServerSessionApplicationService(KtvServerSessionMapper serverSessionMapper,
                                              OrderMapper orderMapper,
                                              KtvPricingPlanProvider pricingPlanProvider,
                                              AuditClient auditClient,
                                              OrderItemMapper orderItemMapper,
                                              KtvSessionMapper ktvSessionMapper,
                                              ResourceStateClient resourceStateClient) {
        this(serverSessionMapper, orderMapper, pricingPlanProvider, auditClient, orderItemMapper, ktvSessionMapper,
                resourceStateClient, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public KtvServerSessionApplicationService(KtvServerSessionMapper serverSessionMapper,
                                              OrderMapper orderMapper,
                                              KtvPricingPlanProvider pricingPlanProvider,
                                              AuditClient auditClient,
                                              OrderItemMapper orderItemMapper,
                                              KtvSessionMapper ktvSessionMapper,
                                              ResourceStateClient resourceStateClient,
                                              OrderAmountApplicationService orderAmounts) {
        this.serverSessionMapper = serverSessionMapper;
        this.orderMapper = orderMapper;
        this.pricingPlanProvider = pricingPlanProvider;
        this.auditClient = auditClient;
        this.orderItemMapper = orderItemMapper;
        this.ktvSessionMapper = ktvSessionMapper;
        this.resourceStateClient = resourceStateClient;
        this.orderAmounts = orderAmounts;
    }

    /** 点服务人员：POST /business/orders/{id}/servers → ORDERED（计时开始）。 */
    @Transactional
    public KtvServerSessionPo order(Long tenantId, Long orderId, Long ktvSessionId,
                                    Long serverResourceId, Long catalogItemId) {
        try {
            OrderPo order = orderMapper.selectById(orderId);
            if (order == null) {
                throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
            }
            if (!"SERVING".equals(order.getStatus())) {
                throw new BusinessException("ORDER_STATUS_INVALID", "仅服务中订单可点服务人员");
            }
            KtvPricingPlan plan = pricingPlanProvider.resolve(tenantId, order.getStoreId());
            LocalDateTime now = LocalDateTime.now();
            KtvServerSessionPo po = new KtvServerSessionPo();
            po.setTenantId(tenantId);
            po.setOrderId(orderId);
            po.setKtvSessionId(ktvSessionId);
            po.setServerResourceId(serverResourceId);
            po.setCatalogItemId(catalogItemId);
            po.setOrderedAt(now);
            po.setBillingUnit(plan.billingUnit().name());
            po.setIncrementMinutes(plan.incrementMinutes());
            po.setRoundingDirection(plan.roundingDirection().name());
            po.setPricePerInc(plan.serverPricePerInc());
            po.setDurationSeconds(0);
            po.setDurationMinutes(0);
            po.setTotalAmount(BigDecimal.ZERO);
            // 币种快照（16_CURRENCY_CONVENTIONS §5）：服务人员费继承所属订单的币种快照。
            po.setCurrencyCode(Currency.parse(order.getCurrencyCode()).code());
            po.setPriceSnapshotJson(plan.toSnapshotJson());
            po.setStatus(KtvServerSessionStatus.ORDERED.name());
            po.setVersion(0);
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            serverSessionMapper.insert(po);
            // 点单即为「本包厢的服务人员」：把服务人员快照写入 KTV 会话，订单列表/房态看板直接读。
            writeServerSnapshotToSession(ktvSessionId, serverResourceId);
            // 点服务人员即开始计费，此前成功/失败都没有留痕。
            recordSessionAudit("order.ktv_server_session.order", "点服务人员", po,
                    "{\"orderId\":" + orderId + ",\"status\":\"ORDERED\",\"serverResourceId\":" + serverResourceId + "}");
            return po;
        } catch (RuntimeException failure) {
            recordFailure("order.ktv_server_session.order", orderId, null, failure);
            throw failure;
        }
    }

    /** 开始服务：ORDERED → SERVING（内部计时，无对外端点，KTV_BUSINESS_01 §5.2）。 */
    @Transactional
    public KtvServerSessionPo start(Long sessionId) {
        KtvServerSessionPo po = require(sessionId);
        if (!KtvServerSessionStatus.ORDERED.name().equals(po.getStatus())) {
            throw new BusinessException("ORDER_STATUS_INVALID", "仅 ORDERED 点单可开始服务");
        }
        po.setStatus(KtvServerSessionStatus.SERVING.name());
        po.setStartedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        applyOptimisticUpdate(po);
        // 开始服务时再固化一次快照：点单接口可能没带 ktvSessionId，或期间换了服务人员。
        writeServerSnapshotToSession(po.getKtvSessionId(), po.getServerResourceId());
        return po;
    }

    /**
     * 把服务人员快照写入 KTV 会话（ord_ktv_session.server_id/server_name）。
     *
     * <p>名称来自资源域（KTV_SERVER 资源名）；资源服务不可达时只写 ID，SQL 用 COALESCE 保留既有名称，
     * 避免一次外部抖动把已固化的服务人员名清空。缺会话/缺服务人员时跳过（不阻断点单）。
     */
    private void writeServerSnapshotToSession(Long ktvSessionId, Long serverResourceId) {
        if (ktvSessionMapper == null || ktvSessionId == null || serverResourceId == null) {
            return;
        }
        String serverName = resourceStateClient == null
                ? null
                : resourceStateClient.room(serverResourceId).map(ResourceStateClient.RoomSnapshot::name).orElse(null);
        ktvSessionMapper.updateServerSnapshot(ktvSessionId, serverResourceId, serverName, LocalDateTime.now());
    }

    /** 结束服务：POST /business/ktv/servers/{id}/end → ENDED，固化时长/金额。 */
    @Transactional
    public KtvServerSessionPo end(Long sessionId) {
        try {
            return doEnd(sessionId);
        } catch (RuntimeException failure) {
            // 结束服务失败留痕（状态冲突/并发冲突/落库失败）：该动作直接固化计费金额。
            recordFailure("order.ktv_server_session.end", null, sessionId, failure);
            throw failure;
        }
    }

    private KtvServerSessionPo doEnd(Long sessionId) {
        KtvServerSessionPo po = require(sessionId);
        String status = po.getStatus();
        if (!KtvServerSessionStatus.ORDERED.name().equals(status)
                && !KtvServerSessionStatus.SERVING.name().equals(status)) {
            throw new BusinessException("ORDER_STATUS_INVALID", "仅 ORDERED/SERVING 点单可结束服务");
        }
        LocalDateTime endedAt = LocalDateTime.now();
        LocalDateTime start = po.getStartedAt() != null ? po.getStartedAt() : po.getOrderedAt();
        long sec = start == null ? 0L : Duration.between(start, endedAt).getSeconds();
        KtvRoundingDirection direction = KtvRoundingDirection.fromCode(po.getRoundingDirection());
        // M 与 n 走与包厢房费同一套舍入算法（默认让利）：33 秒 → M=0 → n=0 → 0 元，不会按整块多收。
        int minutes = UnitTimeFeeCalculator.calculateMinutes(sec, direction);
        long blocks = UnitTimeFeeCalculator.calculateBlocks(sec, po.getIncrementMinutes(), direction);
        long billedMinor = UnitTimeFeeCalculator.calculateTotal(sec, po.getIncrementMinutes(), po.getPricePerInc(), direction);
        // 包厢费（roomFeeIncludesServer）已含 1 名标准服务人员：同一包厢会话下**按创建顺序第一个未取消**的
        // 服务人员会话占用免费名额，不另计费；第 2 名起按各自会话的 price_per_inc 全价计。
        boolean freeQuota = occupiesFreeServerQuota(po);
        long totalMinor = freeQuota ? 0L : billedMinor;
        po.setEndedAt(endedAt);
        po.setDurationSeconds((int) sec);
        po.setDurationMinutes(minutes);
        po.setTotalAmount(BigDecimal.valueOf(totalMinor));
        // 币种快照兜底：点单时已写入；历史/异常行为空时按默认 USD 归一，绝不让快照为空。
        po.setCurrencyCode(Currency.parse(po.getCurrencyCode()).code());
        po.setStatus(KtvServerSessionStatus.ENDED.name());
        po.setUpdatedAt(endedAt);
        applyOptimisticUpdate(po);
        OrderItemPo item = new OrderItemPo();
        item.setTenantId(po.getTenantId());
        item.setOrderId(po.getOrderId());
        item.setItemType("SERVICE");
        item.setCatalogItemId(po.getCatalogItemId());
        item.setResourceId(po.getServerResourceId());
        item.setNameSnapshot(serverFeeItemName(po, freeQuota));
        // 免费名额：单价 0（金额 0），明细照写 —— servers 分区必须完整可解释，不能凭空少一名服务人员。
        item.setUnitPrice(BigDecimal.valueOf(freeQuota || po.getPricePerInc() == null ? 0L : po.getPricePerInc()));
        // 明细数量 = 计费块数（与金额同源），保证「单价 × 数量 = 金额」与账单/订单合计口径一致。
        item.setQuantity(BigDecimal.valueOf(blocks));
        item.setDiscountAmount(BigDecimal.ZERO);
        item.setTaxAmount(BigDecimal.ZERO);
        item.setTotalAmount(po.getTotalAmount());
        // 服务人员费明细与 ord_ktv_server_session 用同一币种快照，账单两侧口径一致。
        item.setCurrencyCode(po.getCurrencyCode());
        // 快照固化「本轮是否占用免费名额」，与 rooms 分区/账单展示同源，事后可复算免了几名。
        item.setPriceSnapshotJson(po.getPriceSnapshotJson() == null
                ? null
                : withFreeServerQuota(po.getPriceSnapshotJson(), freeQuota));
        item.setStatus("ACTIVE");
        item.setSource("MERCHANT");
        item.setCreatedAt(endedAt);
        item.setUpdatedAt(endedAt);
        orderItemMapper.insert(item);
        // 服务人员费刚落入明细，必须同步重算订单金额快照（与结台写房费同一款处理）。
        // 缺了这一步：库内 total_amount 不含服务人员费，账单会出现「服务人员行有钱、合计对不上」——
        // 即 sum(items)+sum(servers)+roomFee != totalAmount，且要等到下一次结算/刷新才补上（中间一直少收）。
        recalculateOrderAmounts(po.getOrderId(), endedAt);
        // 高风险写操作（结束服务并固化金额）审计：异步占位。
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(po.getTenantId())
                .action("order.ktv_server_session.end")
                .actionLabel("服务人员结束服务")
                .resourceType("ktv_server_session")
                .resourceId(String.valueOf(sessionId))
                .idempotencyKey("ktv-server-end:" + sessionId)
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson("{\"orderId\":" + po.getOrderId() + ",\"totalMinor\":" + totalMinor
                        + ",\"freeServerQuota\":" + freeQuota + "}")
                .build());
        return po;
    }

    /** 服务人员明细名：免费名额标注「不另计费」，第 2 名起为「额外服务人员」。 */
    static String serverFeeItemName(KtvServerSessionPo po, boolean freeQuota) {
        return freeQuota
                ? "服务人员#" + po.getServerResourceId() + "（含 1 名标准服务人员，不另计费）"
                : "额外服务人员#" + po.getServerResourceId();
    }

    /**
     * 服务人员费落库后同步订单金额快照：读订单 → 在本实例上重算 → 一次写库（与结台写房费同一款顺序，
     * 避免旧实例覆盖新金额）。订单不存在或未装配金额服务（单测/降级装配）时跳过，绝不阻断结束服务。
     */
    private void recalculateOrderAmounts(Long orderId, LocalDateTime now) {
        if (orderAmounts == null || orderId == null) {
            return;
        }
        OrderPo order = orderMapper.selectById(orderId);
        if (order == null) {
            return;
        }
        orderAmounts.recalculate(order);
        order.setUpdatedAt(now);
        orderMapper.updateById(order);
    }

    /**
     * 本轮是否占用免费名额：同一 KTV 会话（缺 ktvSessionId 时退化为同一订单）下，
     * 按创建顺序（id 升序）排除已取消后的**第一个**服务人员会话即免费名额。
     *
     * <p>只依赖「创建顺序 + 状态」，与 end 调用顺序/并发无关：最早会话被取消时名额顺延给下一个未取消会话，
     * 任意时刻最多只有一个免费名额。查不到同类会话（数据缺失）时按不免费处理，避免静默漏收。
     */
    private boolean occupiesFreeServerQuota(KtvServerSessionPo po) {
        if (serverSessionMapper == null) {
            return false;
        }
        List<KtvServerSessionPo> sameRoom = po.getKtvSessionId() != null
                ? serverSessionMapper.selectByKtvSessionId(po.getKtvSessionId())
                : serverSessionMapper.selectByOrderId(po.getOrderId());
        return freeServerSessionIds(sameRoom).contains(po.getId());
    }

    /**
     * 免费名额服务人员会话 id 集合：按包厢会话（缺 ktvSessionId 时按订单）分组，每组取
     * 「创建顺序最早且未取消」的一个。账单（{@code BillApplicationService}）与本服务共用本方法，
     * 保证「免费标注」与「免费计费」永远同一判定。
     */
    public static java.util.Set<Long> freeServerSessionIds(List<KtvServerSessionPo> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Map<Object, Long> freeByGroup = new java.util.HashMap<>();
        for (KtvServerSessionPo s : sessions) {
            if (s == null || s.getId() == null) {
                continue;
            }
            if (KtvServerSessionStatus.CANCELLED.name().equals(s.getStatus())) {
                continue;
            }
            Object group = s.getKtvSessionId() != null ? s.getKtvSessionId() : ("order:" + s.getOrderId());
            Long current = freeByGroup.get(group);
            if (current == null || s.getId() < current) {
                freeByGroup.put(group, s.getId());
            }
        }
        return new java.util.HashSet<>(freeByGroup.values());
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
    public KtvServerSessionPo cancel(Long sessionId) {
        try {
            KtvServerSessionPo po = require(sessionId);
            if (!KtvServerSessionStatus.ORDERED.name().equals(po.getStatus())) {
                throw new BusinessException("ORDER_STATUS_INVALID", "仅未开始计费的 ORDERED 点单可取消");
            }
            po.setStatus(KtvServerSessionStatus.CANCELLED.name());
            po.setUpdatedAt(LocalDateTime.now());
            applyOptimisticUpdate(po);
            // 取消点单会影响「免费服务人员名额」的顺延结果，此前成功/失败都没有留痕。
            recordSessionAudit("order.ktv_server_session.cancel", "服务人员点单取消", po,
                    "{\"orderId\":" + po.getOrderId() + ",\"status\":\"CANCELLED\"}");
            return po;
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
    private void recordSessionAudit(String action, String actionLabel, KtvServerSessionPo po, String detailJson) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(po.getTenantId())
                .action(action)
                .actionLabel(actionLabel)
                .resourceType("ktv_server_session").resourceId(String.valueOf(po.getId()))
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .detailJson(detailJson)
                .build());
    }

    /** 乐观锁提交：version 不匹配（受影响 0 行）时抛并发冲突。 */
    private void applyOptimisticUpdate(KtvServerSessionPo po) {
        if (serverSessionMapper.updateWithVersion(po) == 0) {
            throw new BusinessException("SESSION_VERSION_CONFLICT", "服务人员点单已被并发修改，请重试");
        }
    }

    private KtvServerSessionPo require(Long sessionId) {
        KtvServerSessionPo po = serverSessionMapper.selectById(sessionId);
        if (po == null) {
            if (serverSessionMapper.selectTenantIdById(sessionId) != null) {
                throw new ApiException(403, "TENANT_SCOPE_DENIED", "无权访问其他租户的服务人员点单");
            }
            throw new BusinessException("ORDER_NOT_FOUND", "服务人员点单不存在");
        }
        return po;
    }
}
