package io.openware.platform.order.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.currency.Currency;
import io.openware.infrastructure.currency.CurrencyResolver;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.order.application.DailySerialNumberGenerator;
import io.openware.platform.order.application.DailySerialNumberGenerator.DocType;
import io.openware.platform.order.application.KtvServerSessionApplicationService;
import io.openware.platform.order.application.KtvSessionApplicationService;
import io.openware.platform.order.application.OrderAmountApplicationService;
import io.openware.platform.order.application.OrderCancellationApplicationService;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.infra.persistence.mapper.CustomerLookupMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.po.KtvServerSessionPo;
import io.openware.platform.order.infra.persistence.po.KtvSessionPo;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/business")
public class OrderController {
    /** 商户侧「查看订单」权限码（与 OrderItemController / OrderBillController 的读路径同码）。 */
    private static final String PERMISSION_VIEW_ORDER = "order.view";

    private final OrderMapper orderMapper;
    private final KtvSessionApplicationService ktvSessionService;
    private final KtvServerSessionApplicationService ktvServerSessionService;
    private final ResourceStateClient resourceStateClient;
    private final AuditClient auditClient;
    private final OrderCancellationApplicationService orderCancellationService;
    private final DailySerialNumberGenerator dailySerialNumberGenerator;
    private final CustomerLookupMapper customerLookupMapper;

    @Autowired
    public OrderController(OrderMapper orderMapper,
                           KtvSessionApplicationService ktvSessionService,
                           KtvServerSessionApplicationService ktvServerSessionService,
                           ResourceStateClient resourceStateClient,
                           AuditClient auditClient,
                           OrderCancellationApplicationService orderCancellationService,
                           DailySerialNumberGenerator dailySerialNumberGenerator,
                           CustomerLookupMapper customerLookupMapper) {
        this.orderMapper = orderMapper;
        this.ktvSessionService = ktvSessionService;
        this.ktvServerSessionService = ktvServerSessionService;
        this.resourceStateClient = resourceStateClient;
        this.auditClient = auditClient;
        this.orderCancellationService = orderCancellationService;
        this.dailySerialNumberGenerator = dailySerialNumberGenerator;
        this.customerLookupMapper = customerLookupMapper;
    }

    /** 兼容既有 Web 层测试装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
    public OrderController(OrderMapper orderMapper,
                           KtvSessionApplicationService ktvSessionService,
                           KtvServerSessionApplicationService ktvServerSessionService,
                           ResourceStateClient resourceStateClient,
                           OrderCancellationApplicationService orderCancellationService,
                           DailySerialNumberGenerator dailySerialNumberGenerator) {
        this(orderMapper, ktvSessionService, ktvServerSessionService, resourceStateClient,
                AuditClient.disabled(), orderCancellationService, dailySerialNumberGenerator, null);
    }

    /** 兼容既有 Web 层测试装配：带审计客户端但没有会员归属查询（读路径不做收窄）。 */
    public OrderController(OrderMapper orderMapper,
                           KtvSessionApplicationService ktvSessionService,
                           KtvServerSessionApplicationService ktvServerSessionService,
                           ResourceStateClient resourceStateClient,
                           AuditClient auditClient,
                           OrderCancellationApplicationService orderCancellationService,
                           DailySerialNumberGenerator dailySerialNumberGenerator) {
        this(orderMapper, ktvSessionService, ktvServerSessionService, resourceStateClient, auditClient,
                orderCancellationService, dailySerialNumberGenerator, null);
    }

    /**
     * 查询订单列表（当前租户，TenantLineInnerInterceptor 自动追加 tenant_id，按创建时间倒序）。
     *
     * <p><b>时间区间</b>：{@code from}/{@code to} 按**下单时间** {@code created_at} 的闭区间筛选，
     * 统一口径见 {@link TimeRangeParams}：{@code yyyy-MM-dd} 的 from/to 分别收口到当天起点与当天末尾
     * （{@code 23:59:59.999}），也接受 {@code yyyy-MM-ddTHH:mm:ss}；为空 = 不筛；
     * {@code from > to} → 400 {@code TIME_RANGE_INVALID}。
     *
     * <p>条件直接落在 {@code created_at} 列上（不包函数，保持索引可用），排序与既有行为不变。
     *
     * <p><b>授权口径（2026-09-19 修复越权）</b>：旧实现既没有权限码也没有归属收窄 ——
     * 任何登录的消费者会话都能拉到本租户**全部订单**（比已修的账单端点范围更大）。
     * 现在与账单/加项同一条规则：
     * <ul>
     *   <li>持 {@code order.view}（收银员/店长/财务/服务人员）→ 行为完全不变；</li>
     *   <li>未持（A380 C 端消费者会话）→ 按 {@code ord_order.customer_id} 收窄到本人
     *       （签名上下文 accountId → cst_member.id，与 {@code GET /me/orders} 同口径）；
     *       解析不出会员档案 → 403 {@code PERMISSION_DENIED}（与账单端点同文案，不新增可探测信息面）；</li>
     *   <li>没有签名上下文（服务间内部调用 / 单测装配）→ 保持既有行为，不额外收窄。</li>
     * </ul>
     */
    @GetMapping("/orders")
    public List<OrderPo> list(@RequestParam(required = false) String from,
                              @RequestParam(required = false) String to) {
        TimeRange range = TimeRangeParams.parse(from, to);
        Long scopedCustomerId = customerScopeForList();
        List<OrderPo> orders = orderMapper.selectList(new LambdaQueryWrapper<OrderPo>()
                .ge(range.hasFrom(), OrderPo::getCreatedAt, range.fromInclusive())
                .le(range.hasTo(), OrderPo::getCreatedAt, range.toInclusive())
                .eq(scopedCustomerId != null, OrderPo::getCustomerId, scopedCustomerId)
                .orderByDesc(OrderPo::getCreatedAt));
        // 订单必须能看出是哪个包厢：从 KTV 会话带出包厢快照与开台中的计时代估算（结台后以账单明细为准）。
        fillSessionProjection(orders);
        return orders;
    }

    /**
     * 订单详情（{@code GET /business/orders/{id}}）。
     *
     * <p><b>为什么补这个端点</b>：A380 收银端 App 的计时加项页 / 结算页一直按
     * {@code GET /business/orders/{id}} 取单（`KtvApiClient.getOrder`），但服务端从未实现该路径，
     * 线上实测 **404**，页面只能落到错误态（包厢、计时、状态全都拿不到）。
     *
     * <p><b>授权与列表同一口径</b>（见 {@link #list}）：持 {@code order.view} 的商户不限；
     * 消费者会话按 {@code ord_order.customer_id} 收窄到本人；两者都不是 → 403 {@code PERMISSION_DENIED}；
     * 订单不存在 → 404 {@code ORDER_NOT_FOUND}；订单存在但不属于本人 → 403（不确认他人订单是否存在，
     * 与列表/账单端点同文案，不新增可探测信息面）。
     */
    @GetMapping("/orders/{id}")
    public OrderPo detail(@PathVariable Long id) {
        Long scopedCustomerId = customerScopeForList();
        OrderPo order = orderMapper.selectById(id);
        if (order == null) {
            throw new ApiException(404, "ORDER_NOT_FOUND", "订单不存在");
        }
        if (scopedCustomerId != null && !scopedCustomerId.equals(order.getCustomerId())) {
            throw new ApiException(403, "PERMISSION_DENIED", "缺少权限: " + PERMISSION_VIEW_ORDER);
        }
        fillSessionProjection(List.of(order));
        return order;
    }

    /**
     * 把 KTV 会话快照（包厢、会话状态、计时估算、服务人员、人数）投影到订单上，
     * 让「订单」本身就能看出是哪个包厢——列表与详情共用，避免两处口径漂移。
     *
     * <p>同时给出**可直接展示的实时合计** {@link OrderPo#getLiveTotalAmount()}：库内
     * {@code total_amount} 里的房费是 ROOM_FEE 明细「上次刷新」的快照，而 {@code roomEstimatedFee}
     * 是此刻的实时估算——客户端把两者相加会把包厢费算两遍（收银台房卡金额比账单多一笔房费）。
     */
    private void fillSessionProjection(List<OrderPo> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        List<Long> orderIds = orders.stream().map(OrderPo::getId).toList();
        var sessions = ktvSessionService.listByOrderIds(orderIds);
        // 明细金额一次批量取（整页订单只查一次，不能逐单查）：既要「已生效明细合计」做合计基数的回退，
        // 也要「其中房费明细合计」做差额法要扣掉的库内房费快照。
        // mock 装配/降级实现可能返回 null，读路径按「没有明细」处理，绝不 NPE。
        Map<Long, KtvSessionApplicationService.OrderAmountSums> itemSums =
                ktvSessionService.orderAmountSumsByOrderIds(orderIds);
        if (itemSums == null) {
            itemSums = Map.of();
        }
        for (OrderPo order : orders) {
            KtvSessionApplicationService.OrderAmountSums sums =
                    itemSums.getOrDefault(order.getId(), KtvSessionApplicationService.OrderAmountSums.ZERO);
            KtvSessionPo session = sessions.get(order.getId());
            if (session == null) {
                // 没有会话（非 KTV 单/未选包厢）：库内合计（或明细回退）就是终值。
                order.setLiveTotalAmount(BigDecimal.valueOf(
                        OrderAmountApplicationService.totalBasisMinor(order, sums.activeItemsMinor())));
                continue;
            }
            ktvSessionService.fillLiveEstimate(session, order.getStoreId());
            order.setSessionId(session.getId());
            order.setRoomResourceId(session.getRoomResourceId());
            order.setRoomName(session.getRoomNameSnapshot());
            order.setRoomCode(session.getRoomCodeSnapshot());
            order.setSessionStatus(session.getStatus());
            order.setPartySize(session.getPartySize());
            order.setServerId(session.getServerId());
            order.setServerName(session.getServerName());
            order.setRoomElapsedSeconds(session.getElapsedSeconds());
            order.setRoomEstimatedFee(session.getEstimatedRoomFee());
            // 订单维度的「消费时间」只能从会话取：订单 created_at（下单/预约时刻）与 completed_at 之间
            // 可能夹着多次开台，直接相减会把多次消费算成一段。
            order.setSessionOpenedAt(session.getOpenedAt());
            order.setSessionClosedAt(session.getClosedAt());
            order.setLiveTotalAmount(liveTotal(order, session, sums));
        }
    }

    /**
     * 实时应付合计（**最小货币单位**：金额列里存的就是分/cent 整数；返回 scale=0 的 BigDecimal，
     * 与库内 {@code totalAmount}（decimal(20,6) 的同一数值快照）是同一个货币单位）：
     * <ul>
     *   <li>会话开台中/挂单中（OPEN/PAUSED）**且算得出实时房费**：库内合计 − 房费明细快照 + 实时房费估算
     *       （「差额法」，与 {@code BillApplicationService#buildBill} 同一实现，保证客户端看到的合计
     *       与账单 {@code totalMinor} 是同一个数）；</li>
     *   <li>未开台/已结台/没有实时房费（一口价套餐）：库内合计即终值，原样返回。</li>
     * </ul>
     *
     * <p>这里的实时估算来自 {@code session.estimatedRoomFee}（由
     * {@code KtvSessionApplicationService#fillLiveEstimate} → {@code liveRoomFee} 算出），
     * 本方法**只做加减，不重新实现任何计费公式**；未取到估算（如一口价套餐不按时长计费）时按无实时房费处理，
     * 此时库内合计就是终值。结果兜底不为负，避免脏数据导致展示负数。
     *
     * <p>合计基数（库内合计为 0 时按明细回退）与差额法都委托
     * {@link OrderAmountApplicationService}，账单与投影因此共用同一份口径。
     */
    private static BigDecimal liveTotal(OrderPo order, KtvSessionPo session,
                                        KtvSessionApplicationService.OrderAmountSums sums) {
        long basisMinor = OrderAmountApplicationService.totalBasisMinor(order, sums.activeItemsMinor());
        Long liveRoomFeeMinor = isOpenSession(session) ? session.getEstimatedRoomFee() : null;
        if (liveRoomFeeMinor == null) {
            return BigDecimal.valueOf(basisMinor);
        }
        return BigDecimal.valueOf(OrderAmountApplicationService.withRoomFeeReplaced(
                basisMinor, sums.roomFeeItemsMinor(), liveRoomFeeMinor));
    }

    /** 会话是否「开台中」（OPEN/PAUSED）：只有这两个状态的房费还在随时间增长。 */
    private static boolean isOpenSession(KtvSessionPo session) {
        return session != null && ("OPEN".equals(session.getStatus()) || "PAUSED".equals(session.getStatus()));
    }

    /** 按订单查 KTV 会话（点单/结台/续台/转台需 sessionId）。 */
    @GetMapping("/orders/{id}/session")
    public KtvSessionPo session(@PathVariable Long id) {
        return ktvSessionService.findByOrderId(id);
    }

    /**
     * 订单列表的可见范围：商户权限码优先（null = 不限）；消费者会话收窄到本人；无上下文保持既有行为。
     *
     * <p>见 {@link #list} 的授权口径说明。返回 null 表示不加 {@code customer_id} 条件。
     */
    private Long customerScopeForList() {
        if (hasPermission(PERMISSION_VIEW_ORDER)) {
            return null;
        }
        TenantContext context = TenantContextHolder.get();
        if (context == null) {
            // 无签名上下文：服务间内部调用或单测装配，保持既有行为（HTTP 入口必带上下文）。
            return null;
        }
        // findMemberId 的入参是原始 long：上下文字段为空时先判空，绝不让拆箱 NPE 变成 500。
        Long tenantId = context.tenantId();
        Long accountId = context.accountId();
        Long memberId = customerLookupMapper == null || tenantId == null || accountId == null
                ? null
                : customerLookupMapper.findMemberId(tenantId, accountId);
        if (memberId == null) {
            // 既不是商户也不是会员：与账单/自助加项同一文案，不泄露订单是否存在。
            throw new ApiException(403, "PERMISSION_DENIED", "缺少权限: " + PERMISSION_VIEW_ORDER);
        }
        return memberId;
    }

    /** 当前签名上下文是否持有权限码（消费端/B 端权限都来自签名 token，客户端无法伪造）。 */
    private static boolean hasPermission(String permissionCode) {
        TenantContext context = TenantContextHolder.get();
        return context != null && context.permissions() != null
                && context.permissions().contains(permissionCode);
    }

    /**
     * 快速开台：创建 KTV 订单 + 包厢会话（DRAFT→SERVING 简化，先建 RESERVED 会话并回填 sessionId）。
     *
     * <p><b>幂等</b>：请求头带 {@code Idempotency-Key} 时按 (tenant_id, idempotency_key) 回放已有订单
     * （B 端 App 的写请求拦截器每次都会带这个头，此前服务端忽略它 → 一次重试就多一张 DRAFT 单）。
     * 不带该头时行为完全不变；并发重复提交由唯一键兜底，命中后回放先写入的那张单。
     *
     * <p><b>单号规则</b>：{@code O<yyyyMMdd><当日序号>}（如 {@code O202609190001}），
     * 日期是**门店营业日**（Asia/Shanghai + 04:00 切点），序号按租户每日从 0001 递增，
     * 由 {@link DailySerialNumberGenerator} 在数据库里分配（并发安全、失败关闭：
     * 序号服务不可用 → 503 {@code DOC_NO_SEQUENCE_UNAVAILABLE}，不降级为时间戳）。
     * 历史订单号（{@code O<毫秒时间戳>}）原样保留，不做回填。
     */
    @PostMapping("/orders")
    public OrderPo createOrder(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                               @RequestBody CreateOrderRequest req) {
        PermissionGuard.require("ktv.session.open");
        TenantContext ctx = requireContext();
        Long tenantId = ctx.tenantId();
        Long organizationId = ctx.organizationId();
        Long storeId = ctx.storeId();
        if (organizationId == null || storeId == null) {
            throw new ApiException(400, "ORDER_CONTEXT_MISSING", "缺少组织/门店上下文，请先选择门店上下文");
        }
        // 幂等回放：同租户同 Idempotency-Key 已开过台则直接返回那张单（含会话 id），不再建单、不再建房态会话。
        OrderPo replayed = findByIdempotencyKey(tenantId, idempotencyKey);
        if (replayed != null) {
            return withSession(replayed);
        }
        // 占用/清洁中的包厢不能再开台：必须在落订单之前校验，否则失败路径会留下一张没有包厢、
        // 没有会话的孤儿 DRAFT 单（房态看板会把它当成一个「使用中」的幽灵包厢）。
        if ("KTV".equals(req.businessType()) && req.resourceId() != null) {
            resourceStateClient.state(req.resourceId()).ifPresent(state -> {
                if (!state.available()) {
                    throw new ApiException(422, "ROOM_UNAVAILABLE",
                            "包厢「" + (state.name() == null ? req.resourceId() : state.name()) + "」"
                                    + (state.reason() == null ? "当前不可用" : state.reason() + "，暂不可开台"));
                }
            });
        }

        OrderPo po = new OrderPo();
        po.setTenantId(tenantId);
        po.setOrganizationId(organizationId);
        po.setStoreId(storeId);
        po.setOrderNo(dailySerialNumberGenerator.next(DocType.ORDER, tenantId));
        po.setIdempotencyKey(blankToNull(idempotencyKey));
        po.setBusinessType(req.businessType());
        po.setStatus("DRAFT");
        po.setCurrencyCode(resolveCurrencyCode(req.currencyCode()));
        po.setSubtotalAmount(BigDecimal.ZERO);
        po.setDiscountAmount(BigDecimal.ZERO);
        po.setTaxAmount(BigDecimal.ZERO);
        po.setTotalAmount(BigDecimal.ZERO);
        po.setPaidAmount(BigDecimal.ZERO);
        po.setRefundableAmount(BigDecimal.ZERO);
        po.setVersion(0);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        try {
            orderMapper.insert(po);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // 并发重复提交：唯一键把第二个请求挡下，回放先写入的那张单（与预约创建同款处理）。
            OrderPo winner = findByIdempotencyKey(tenantId, idempotencyKey);
            if (winner == null) {
                throw duplicate;
            }
            return withSession(winner);
        }

        // 同步创建包厢会话（RESERVED），关联 orderId + 包厢 resourceId，返回 sessionId 供开台/计时/结台使用。
        if ("KTV".equals(req.businessType()) && req.resourceId() != null) {
            KtvSessionPo session = ktvSessionService.create(tenantId, po.getId(), req.resourceId());
            po.setSessionId(session.getId());
        }
        return po;
    }

    /**
     * 幂等键查询（租户 + 键唯一）：键为空直接返回 null（不带头的调用方保持既有行为）。
     * 与 {@code ReservationApplicationService.findByIdempotencyKey} 同一口径（LIMIT 1 + 租户条件）。
     */
    private OrderPo findByIdempotencyKey(Long tenantId, String idempotencyKey) {
        String key = blankToNull(idempotencyKey);
        if (key == null || tenantId == null) {
            return null;
        }
        return orderMapper.selectOne(new LambdaQueryWrapper<OrderPo>()
                .eq(OrderPo::getTenantId, tenantId)
                .eq(OrderPo::getIdempotencyKey, key)
                .last("LIMIT 1"));
    }

    /**
     * 回放已有订单时补上会话 id（客户端按 sessionId 继续开台/点单，缺了会表现为「没有会话」）。
     *
     * <p>用 {@code listByOrderIds} 而不是 {@code findByOrderId}：后者在没有会话时抛
     * {@code ORDER_NOT_FOUND}（404）——非 KTV 单、或建单时未选包厢的单本来就没有会话，
     * 幂等回放不能因此变成 404（端到端验证时踩到过）。
     */
    private OrderPo withSession(OrderPo order) {
        if (order == null || order.getId() == null) {
            return order;
        }
        KtvSessionPo session = ktvSessionService.listByOrderIds(List.of(order.getId())).get(order.getId());
        if (session != null) {
            order.setSessionId(session.getId());
        }
        return order;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 确认订单：DRAFT → WAITING_SETTLEMENT（加项完成，进入结算）。 */
    @PostMapping("/orders/{id}/confirm")
    public OrderPo confirm(@PathVariable Long id) {
        PermissionGuard.require("order.settle");
        OrderPo po = orderMapper.selectById(id);
        if (po == null) {
            if (orderMapper.selectTenantIdById(id) != null) {
                throw new ApiException(403, "TENANT_SCOPE_DENIED", "无权访问其他租户的订单");
            }
            throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        }
        if (!"DRAFT".equals(po.getStatus())) {
            throw new BusinessException("ORDER_STATUS_INVALID", "仅 DRAFT 订单可确认");
        }
        po.setStatus("WAITING_SETTLEMENT");
        po.setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(po);
        return po;
    }

    /** 开台：可选登记到店人数（partySize）；非空时必须 > 0 且不超过包厢容量，否则 400。 */
    @PostMapping("/ktv/sessions/{id}/open")
    public KtvSessionPo open(@PathVariable Long id, @RequestBody OpenRequest req) {
        PermissionGuard.require("ktv.session.open");
        return ktvSessionService.open(id, req == null ? null : req.freeWaitMinutes(),
                req == null ? null : req.partySize());
    }

    @PostMapping("/ktv/sessions/{id}/pause")
    public KtvSessionPo pause(@PathVariable Long id) {
        PermissionGuard.require("ktv.session.open");
        return ktvSessionService.pause(id);
    }

    @PostMapping("/ktv/sessions/{id}/resume")
    public KtvSessionPo resume(@PathVariable Long id) {
        PermissionGuard.require("ktv.session.open");
        return ktvSessionService.resume(id);
    }

    @PostMapping("/ktv/sessions/{id}/close")
    public KtvSessionPo close(@PathVariable Long id) {
        PermissionGuard.require("ktv.session.operate");
        return ktvSessionService.close(id);
    }

    @PostMapping("/ktv/sessions/{id}/cancel")
    public KtvSessionPo cancel(@PathVariable Long id) {
        PermissionGuard.require("ktv.session.open");
        return ktvSessionService.cancel(id);
    }

    /** 点服务人员：POST /business/orders/{id}/servers → ORDERED（计时开始，KTV_BUSINESS_01 §5.2）。 */
    @PostMapping("/orders/{id}/servers")
    public KtvServerSessionPo orderServer(@PathVariable Long id, @RequestBody OrderServerRequest req) {
        PermissionGuard.require("ktv.server.order");
        return ktvServerSessionService.order(requireContext().tenantId(), id, req.ktvSessionId(), req.serverResourceId(), req.catalogItemId());
    }

    /** 挂单：SERVING 内操作标记，不结台、不改商业状态（KTV_BUSINESS_01 §11.1）。 */
    @PostMapping("/orders/{id}/hold")
    public OrderPo hold(@PathVariable Long id, @RequestBody HoldRequest req) {
        PermissionGuard.require("order.hold");
        try {
            OrderPo po = requireOrder(id);
            if (!"SERVING".equals(po.getStatus())) {
                throw new BusinessException("ORDER_STATUS_INVALID", "仅 SERVING 订单可挂单");
            }
            po.setHoldReason(req.reason());
            po.setHoldAt(LocalDateTime.now());
            po.setUpdatedAt(LocalDateTime.now());
            orderMapper.updateById(po);
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(po.getTenantId())
                    .storeId(po.getStoreId())
                    .action("order.hold")
                    .resourceType("ord_order").resourceId(String.valueOf(id))
                    .resourceName(po.getOrderNo())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .idempotencyKey("order-hold:" + id + ":" + po.getHoldAt())
                    .detailJson("{\"reason\":\"" + safe(req.reason()) + "\"}")
                    .build());
            return po;
        } catch (RuntimeException failure) {
            recordFailure("order.hold", "ord_order", id, null, failure);
            throw failure;
        }
    }

    /** 解除挂单。 */
    @PostMapping("/orders/{id}/unhold")
    public OrderPo unhold(@PathVariable Long id) {
        PermissionGuard.require("order.hold");
        try {
            OrderPo po = requireOrder(id);
            po.setHoldReason(null);
            po.setHoldAt(null);
            po.setUpdatedAt(LocalDateTime.now());
            orderMapper.updateById(po);
            // 解挂此前完全没有留痕：解挂后订单不再被挂牌提示，是否有人解挂必须可回溯。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(po.getTenantId())
                    .storeId(po.getStoreId())
                    .action("order.unhold")
                    .actionLabel("订单解挂")
                    .resourceType("ord_order").resourceId(String.valueOf(id))
                    .resourceName(po.getOrderNo())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .idempotencyKey("order-unhold:" + id + ":" + po.getUpdatedAt())
                    .build());
            return po;
        } catch (RuntimeException failure) {
            recordFailure("order.unhold", "ord_order", id, null, failure);
            throw failure;
        }
    }

    /**
     * 取消/作废/挂单类写操作的失败留痕：动作码与成功路径同码，{@code result=FAILURE} + 稳定 errorCode。
     *
     * <p>不带幂等键（同一动作重复失败必须各自留痕，也不得覆盖成功路径的稳定键）；
     * detail 只放资源标识，不含原因等自由文本与金额。
     *
     * <p>注意：{@link #cancelOrder}/{@link #voidOrder} 本身不再重复留痕——它们只是把请求转交给
     * {@link OrderCancellationApplicationService}，该服务已在失败出口记录同码 FAILURE，
     * 在此再记一条会让一次失败产生两条同义审计。
     */
    private void recordFailure(String action, String resourceType, Long resourceId, String resourceName,
                               RuntimeException failure) {
        TenantContext context = TenantContextHolder.get();
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(context == null ? null : context.tenantId())
                .storeId(context == null ? null : context.storeId())
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId == null ? null : String.valueOf(resourceId))
                .resourceName(resourceName)
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .build());
    }

    /** 转台：换包厢，计时继承（累计）不重置（KTV_BUSINESS_01 §11.2）。 */
    @PostMapping("/orders/{id}/transfer")
    public KtvSessionPo transfer(@PathVariable Long id, @RequestBody TransferRequest req) {
        PermissionGuard.require("order.transfer");
        OrderPo order = requireOrder(id);
        KtvSessionPo session = ktvSessionService.findByOrderId(order.getId());
        return ktvSessionService.transfer(session.getId(), req.targetResourceId());
    }

    /**
     * 取消订单（运营代客取消）：POST /business/orders/{id}/cancel，body {@code {reason}}，权限沿用 {@code order.void}。
     *
     * <p>与既有作废共用同一段规则（见 {@link OrderCancellationApplicationService}）：
     * 原因必填（400 {@code CANCEL_REASON_REQUIRED}）；仅未完成订单可取消（409 {@code ORDER_STATUS_INVALID}）；
     * 已收款拦截（409 {@code ORDER_HAS_PAYMENT_REFUND_FIRST}）；同一事务内取消活动包厢会话并释放占用；
     * 审计动作码 {@code order.cancel}（中文「取消订单」），detail 含前后状态/原因/释放的会话与包厢。
     * 重复取消同一订单幂等返回既有结果且不重复留痕。
     */
    @PostMapping("/orders/{id}/cancel")
    public OrderPo cancelOrder(@PathVariable Long id, @RequestBody(required = false) CancelRequest req) {
        PermissionGuard.require("order.void");
        return orderCancellationService.cancel(id, req == null ? null : req.reason());
    }

    /**
     * 作废：订单 → VOIDED（KTV_BUSINESS_01 §11.3）。
     * 与「取消订单」共用状态校验/已收款拦截/包厢释放实现，仅审计码仍为 {@code order.void}，
     * 原因保持选填且已作废仍 409（既有调用方行为不变）。
     */
    @PostMapping("/orders/{id}/void")
    public OrderPo voidOrder(@PathVariable Long id, @RequestBody(required = false) VoidRequest req) {
        PermissionGuard.require("order.void");
        return orderCancellationService.voidOrder(id, req == null ? null : req.reason());
    }


    /** 暂停修正：店长/财务修正 paused_seconds（KTV_BUSINESS_01 §11.5）。 */
    @PostMapping("/ktv/sessions/{id}/correct-pause")
    public KtvSessionPo correctPause(@PathVariable Long id, @RequestBody CorrectPauseRequest req) {
        PermissionGuard.require("ktv.session.correct_pause");
        return ktvSessionService.correctPause(id, req.pausedSeconds());
    }

    /** 订单查询（404 不存在 / 403 跨租户）：口径集中在取消服务，避免多处各写一套。 */
    private OrderPo requireOrder(Long id) {
        return orderCancellationService.requireOrder(id);
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0 || context.organizationId() == null || context.storeId() == null) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少有效的租户/组织/门店上下文");
        }
        return context;
    }

    /** 审计详情里的自由文本：转义引号，避免拼出非法 JSON（审计落库失败会丢操作日志）。 */
    private static String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 开台币种（16_CURRENCY_CONVENTIONS §1/§2/§5）：
     * 显式传入时必须是受支持币种，否则 400 {@code CURRENCY_UNSUPPORTED}（非法值不得静默降级）；
     * 未传/空白时取当时租户币种（{@link CurrencyResolver#currentCode()}，缺省 USD）。
     * 这里写入的就是该订单的**币种快照**，之后租户改设置不得改写它（历史单据锁定币种）。
     */
    private static String resolveCurrencyCode(String requested) {
        if (requested != null && !requested.isBlank() && !Currency.isSupported(requested)) {
            throw new ApiException(400, "CURRENCY_UNSUPPORTED", "不支持的币种: " + requested);
        }
        return requested == null || requested.isBlank()
                ? CurrencyResolver.currentCode()
                : Currency.parse(requested).code();
    }

    public record CreateOrderRequest(Long tenantId, Long organizationId, Long storeId, String businessType, String currencyCode, Long resourceId) {}
    public record OpenRequest(Integer freeWaitMinutes, Integer partySize) {}
    public record OrderServerRequest(Long tenantId, Long ktvSessionId, Long serverResourceId, Long catalogItemId) {}
    public record HoldRequest(String reason) {}
    public record TransferRequest(Long targetResourceId) {}
    public record VoidRequest(String reason) {}
    /** 取消订单请求体：{@code reason} 必填（空白 → 400 CANCEL_REASON_REQUIRED），≤255 字符。 */
    public record CancelRequest(String reason) {}
    public record CorrectPauseRequest(Integer pausedSeconds) {}
}
