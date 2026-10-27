package io.openware.platform.order.application;

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
import io.openware.platform.order.application.dto.OrderView;
import io.openware.platform.order.domain.ktv.model.KtvSession;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.RefundStatus;
import io.openware.platform.order.domain.order.model.TradeStatus;
import io.openware.platform.order.domain.order.repository.MemberLookupProvider;
import io.openware.platform.order.domain.order.repository.OrderQuery;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.repository.Persisted;
import io.openware.platform.order.infra.client.ResourceStateClient;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单读/写编排：列表、详情、创建、确认、挂单、解挂。
 *
 * <p>授权、时间区间、会话投影、实时合计、创建幂等、单号分配、房态校验与审计都在应用服务完成；
 * 控制器只做协议转换（standards/10 §应用服务规则）。
 */
@Service
public class OrderApplicationService {
  /** 商户侧「查看订单」权限码（与订单明细/账单读路径同码）。 */
  private static final String PERMISSION_VIEW_ORDER = "order.view";

  private final OrderRepository orderRepository;
  private final KtvSessionApplicationService ktvSessionService;
  private final ResourceStateClient resourceStateClient;
  private final AuditClient auditClient;
  private final OrderCancellationApplicationService orderCancellationService;
  private final DailySerialNumberGenerator dailySerialNumberGenerator;
  private final MemberLookupProvider memberLookupProvider;

  public OrderApplicationService(OrderRepository orderRepository,
                                 KtvSessionApplicationService ktvSessionService,
                                 ResourceStateClient resourceStateClient,
                                 AuditClient auditClient,
                                 OrderCancellationApplicationService orderCancellationService,
                                 DailySerialNumberGenerator dailySerialNumberGenerator,
                                 MemberLookupProvider memberLookupProvider) {
    this.orderRepository = orderRepository;
    this.ktvSessionService = ktvSessionService;
    this.resourceStateClient = resourceStateClient;
    this.auditClient = auditClient;
    this.orderCancellationService = orderCancellationService;
    this.dailySerialNumberGenerator = dailySerialNumberGenerator;
    this.memberLookupProvider = memberLookupProvider;
  }

  /**
   * 查询订单列表（当前租户，按创建时间倒序）。
   *
   * <p>时间区间按**下单时间**的闭区间筛选；授权口径：持 {@code order.view} 的商户不限；
   * 消费者会话按 {@code ord_order.customer_id} 收窄到本人；无上下文保持既有行为。
   */
  @Transactional(readOnly = true)
  public List<OrderView> list(String from, String to) {
    TimeRange range = TimeRangeParams.parse(from, to);
    Long scopedCustomerId = customerScopeForList();
    // 条件只表达「下单时间闭区间 + 可选会员收窄 + 倒序」，SQL 由适配器落成（created_at 不包函数）。
    List<Order> orders = orderRepository.list(OrderQuery.newestFirst()
            .from(range.fromInclusive())
            .until(range.toInclusive())
            .withCustomer(scopedCustomerId)).stream()
        .map(Persisted::aggregate)
        .toList();
    return fillSessionProjection(orders);
  }

  /**
   * 订单详情。授权与列表同一口径：持 {@code order.view} 的商户不限；消费者会话按
   * {@code ord_order.customer_id} 收窄到本人；订单不存在 404，不属于本人 403。
   */
  @Transactional(readOnly = true)
  public OrderView detail(Long id) {
    Long scopedCustomerId = customerScopeForList();
    Order order = orderRepository.findById(id).orElse(null);
    if (order == null) {
      throw new ApiException(404, "ORDER_NOT_FOUND", "订单不存在");
    }
    if (scopedCustomerId != null && !scopedCustomerId.equals(order.customerId())) {
      throw new ApiException(403, "PERMISSION_DENIED", "缺少权限: " + PERMISSION_VIEW_ORDER);
    }
    return fillSessionProjection(List.of(order)).get(0);
  }

  /**
   * 快速开台：创建 KTV 订单 + 包厢会话（交易状态 DRAFT，先建 RESERVED 会话并回填 sessionId；
   * 开台/结台只改会话状态，订单交易状态在结台进入结算时才推进）。
   *
   * <p>幂等：请求头带 {@code Idempotency-Key} 时按 (tenant_id, idempotency_key) 回放已有订单；
   * 并发重复提交由唯一键兜底，命中后回放先写入的那张单。
   */
  public OrderView createOrder(String idempotencyKey, CreateOrderCommand req) {
    PermissionGuard.require("ktv.session.open");
    TenantContext ctx = requireContext();
    Long tenantId = ctx.tenantId();
    Long organizationId = ctx.organizationId();
    Long storeId = ctx.storeId();
    if (organizationId == null || storeId == null) {
      throw new ApiException(400, "ORDER_CONTEXT_MISSING", "缺少组织/门店上下文，请先选择门店上下文");
    }
    // 幂等回放：同租户同 Idempotency-Key 已开过台则直接返回那张单（含会话 id），不再建单、不再建房态会话。
    Order replayed = findByIdempotencyKey(tenantId, idempotencyKey);
    if (replayed != null) {
      return withSession(replayed);
    }
    // 占用/清洁中的包厢不能再开台：必须在落订单之前校验，否则失败路径会留下一张没有包厢、
    // 没有会话的孤儿 DRAFT 单（房态看板会把它当成一个「使用中」的幽灵包厢）。
    String businessType = effectiveBusinessType(ctx, req.businessType());
    if ("KTV".equals(businessType) && req.resourceId() != null) {
      resourceStateClient.state(req.resourceId()).ifPresent(state -> {
        if (!state.available()) {
          throw new ApiException(422, "ROOM_UNAVAILABLE",
              "包厢「" + (state.name() == null ? req.resourceId() : state.name()) + "」"
                  + (state.reason() == null ? "当前不可用" : state.reason() + "，暂不可开台"));
        }
      });
    }

    // 领域模型不可变：直接构造落库聚合（金额六列全 0、交易状态 DRAFT、退款状态 NONE、版本 0、下单人留空 → DDL 默认 0 = 系统）。
    Order order = new Order(null, tenantId, organizationId, storeId,
        dailySerialNumberGenerator.next(DailySerialNumberGenerator.DocType.ORDER, tenantId),
        blankToNull(idempotencyKey), businessType, null,
        TradeStatus.DRAFT.name(), RefundStatus.NONE.name(), resolveCurrencyCode(req.currencyCode()),
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, null, null, null, null, 0);
    Order created;
    try {
      created = orderRepository.insert(order, null);
    } catch (DuplicateKeyException duplicate) {
      // 并发重复提交：唯一键把第二个请求挡下，回放先写入的那张单（与预约创建同款处理）。
      Order winner = findByIdempotencyKey(tenantId, idempotencyKey);
      if (winner == null) {
        throw duplicate;
      }
      return withSession(winner);
    }

    // 同步创建包厢会话（RESERVED），关联 orderId + 包厢 resourceId，返回 sessionId 供开台/计时/结台使用。
    OrderView view = OrderView.from(created);
    if ("KTV".equals(businessType) && req.resourceId() != null) {
      KtvSession session = ktvSessionService.create(tenantId, created.id(), req.resourceId());
      view = view.withSessionId(session.id());
    }
    return view;
  }

  /**
   * 酒店预订生成订单应收。酒店预订不是 KTV 开台，不复用 createOrder 的房态/会话逻辑；
   * 只创建带 total_amount 的 ord_order，供 payment-service collect 校验应收。
   */
  @Transactional
  public Long createHotelReceivable(Long tenantId, Long organizationId, Long storeId, String bookingNo,
                                    String currencyCode, long totalMinor) {
    return createHotelReceivable(tenantId, organizationId, storeId, bookingNo, currencyCode,
        totalMinor, null);
  }

  /** 酒店应收：C 端下单时带 customerId，支付归属校验不再依赖人工补写。 */
  @Transactional
  public Long createHotelReceivable(Long tenantId, Long organizationId, Long storeId, String bookingNo,
                                    String currencyCode, long totalMinor, Long customerId) {
    if (tenantId == null || organizationId == null || storeId == null || bookingNo == null || totalMinor <= 0) {
      throw new ApiException(400, "HOTEL_RECEIVABLE_INVALID", "酒店订单应收参数不完整");
    }
    String idempotencyKey = "hotel-booking:" + bookingNo;
    Order existing = findByIdempotencyKey(tenantId, idempotencyKey);
    if (existing != null) {
      return existing.id();
    }
    BigDecimal total = BigDecimal.valueOf(totalMinor);
    Order order = new Order(null, tenantId, organizationId, storeId,
        dailySerialNumberGenerator.next(DailySerialNumberGenerator.DocType.ORDER, tenantId),
        idempotencyKey, "HOTEL", customerId,
        TradeStatus.DRAFT.name(), RefundStatus.NONE.name(), resolveCurrencyCode(currencyCode),
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, total, BigDecimal.ZERO, BigDecimal.ZERO,
        null, null, null, null, 0);
    try {
      return orderRepository.insert(order, null).id();
    } catch (DuplicateKeyException duplicate) {
      Order winner = findByIdempotencyKey(tenantId, idempotencyKey);
      if (winner == null) {
        throw duplicate;
      }
      return winner.id();
    }
  }

  /** 确认订单：交易状态 DRAFT → PENDING_PAYMENT（加项完成，进入结算）。 */
  public OrderView confirm(Long id) {
    PermissionGuard.require("order.settle");
    Order order = orderRepository.findById(id).orElse(null);
    if (order == null) {
      if (orderRepository.findTenantIdById(id).isPresent()) {
        throw new ApiException(403, "TENANT_SCOPE_DENIED", "无权访问其他租户的订单");
      }
      throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
    }
    if (order.trade() != TradeStatus.DRAFT) {
      throw new BusinessException("ORDER_STATUS_INVALID", "仅 DRAFT 订单可确认");
    }
    // 条件更新：只有库中交易状态仍是 DRAFT 才转换成功。上面那句比对不是并发保护——
    // 两个并发确认都会读到 DRAFT，真正的判定在这条 UPDATE 的 trade_status 条件上。
    LocalDateTime now = LocalDateTime.now();
    if (!orderRepository.updateTradeStatusGuarded(id, TradeStatus.DRAFT.name(),
        TradeStatus.PENDING_PAYMENT.name(), now)) {
      throw new BusinessException("ORDER_STATUS_INVALID", "订单状态已被其他请求变更，请重试");
    }
    return OrderView.from(order.withTradeStatus(TradeStatus.PENDING_PAYMENT.name()));
  }

  /** 挂单：开台中（KTV 会话 OPEN/PAUSED）的操作标记，不结台、不改交易状态。 */
  public OrderView hold(Long id, String reason) {
    PermissionGuard.require("order.hold");
    try {
      Order order = requireOrder(id);
      // 挂单是「服务中」的动作：交易状态必须还是 DRAFT（未进入结算），且存在开台中/挂单中的 KTV 会话。
      if (order.trade() != TradeStatus.DRAFT || !hasOngoingSession(id)) {
        throw new BusinessException("ORDER_STATUS_INVALID", "仅开台中（未结算）订单可挂单");
      }
      // 条件更新：只有 hold_at 仍为空才算挂单成功。按 status 做条件挡不住并发挂单
      // （挂单不改商业状态），两次挂单的理由与时间会互相覆盖。
      LocalDateTime holdAt = LocalDateTime.now();
      if (!orderRepository.holdIfNotHeld(id, holdAt, reason, holdAt)) {
        throw new BusinessException("ORDER_STATUS_INVALID", "订单已处于挂单中，请重试");
      }
      auditClient.recordAsync(AuditClient.AuditRecord.builder()
          .tenantId(order.tenantId())
          .storeId(order.storeId())
          .action("order.hold")
          .resourceType("ord_order").resourceId(String.valueOf(id))
          .resourceName(order.orderNo())
          .result(AuditClient.AuditRecord.RESULT_SUCCESS)
          .idempotencyKey("order-hold:" + id + ":" + holdAt)
          .detailJson("{\"reason\":\"" + safe(reason) + "\"}")
          .build());
      return OrderView.from(order.held(reason, holdAt));
    } catch (RuntimeException failure) {
      recordFailure("order.hold", "ord_order", id, null, failure);
      throw failure;
    }
  }

  /** 解除挂单。 */
  public OrderView unhold(Long id) {
    PermissionGuard.require("order.hold");
    try {
      Order order = requireOrder(id);
      // 条件更新：只有确实处于挂单中才算解挂成功，与 holdIfNotHeld 对称。
      LocalDateTime now = LocalDateTime.now();
      if (!orderRepository.unholdIfHeld(id, now)) {
        throw new BusinessException("ORDER_STATUS_INVALID", "订单当前并未挂单，请重试");
      }
      // 解挂此前完全没有留痕：解挂后订单不再被挂牌提示，是否有人解挂必须可回溯。
      auditClient.recordAsync(AuditClient.AuditRecord.builder()
          .tenantId(order.tenantId())
          .storeId(order.storeId())
          .action("order.unhold")
          .actionLabel("订单解挂")
          .resourceType("ord_order").resourceId(String.valueOf(id))
          .resourceName(order.orderNo())
          .result(AuditClient.AuditRecord.RESULT_SUCCESS)
          .idempotencyKey("order-unhold:" + id + ":" + now)
          .build());
      return OrderView.from(order.unheld(now));
    } catch (RuntimeException failure) {
      recordFailure("order.unhold", "ord_order", id, null, failure);
      throw failure;
    }
  }

  /**
   * 把 KTV 会话快照投影到订单上，并给出可直接展示的实时合计。
   *
   * <p>库内 {@code total_amount} 里的房费是 ROOM_FEE 明细「上次刷新」的快照，而
   * {@code roomEstimatedFee} 是此刻的实时估算——客户端把两者相加会把包厢费算两遍；
   * 差额法由 {@link OrderAmountApplicationService} 统一实现。
   */
  private List<OrderView> fillSessionProjection(List<Order> orders) {
    if (orders == null || orders.isEmpty()) {
      return List.of();
    }
    List<Long> orderIds = orders.stream().map(Order::id).toList();
    var sessions = ktvSessionService.listByOrderIds(orderIds);
    // 明细金额一次批量取（整页订单只查一次，不能逐单查）。
    // mock 装配/降级实现可能返回 null，读路径按「没有明细」处理，绝不 NPE。
    Map<Long, KtvSessionApplicationService.OrderAmountSums> itemSums =
        ktvSessionService.orderAmountSumsByOrderIds(orderIds);
    if (itemSums == null) {
      itemSums = Map.of();
    }
    List<OrderView> views = new ArrayList<>(orders.size());
    for (Order order : orders) {
      KtvSessionApplicationService.OrderAmountSums sums =
          itemSums.getOrDefault(order.id(), KtvSessionApplicationService.OrderAmountSums.ZERO);
      OrderView view = OrderView.from(order);
      KtvSession session = sessions.get(order.id());
      if (session == null) {
        // 没有会话（非 KTV 单/未选包厢）：库内合计（或明细回退）就是终值。
        views.add(view.withLiveTotalAmount(BigDecimal.valueOf(
            OrderAmountApplicationService.totalBasisMinor(order, sums.activeItemsMinor()))));
        continue;
      }
      // 实时计时/实时房费是读投影（领域模型上没有它们）：非开台中会话或一口价套餐返回 null。
      KtvSessionApplicationService.LiveEstimate live = ktvSessionService.liveEstimate(session,
          order.storeId());
      views.add(view.withSessionProjection(session.id(), session.roomResourceId(),
          session.roomNameSnapshot(), session.roomCodeSnapshot(), session.status(), session.partySize(),
          session.serverId(), session.serverName(),
          live == null ? null : live.elapsedSeconds(),
          live == null ? null : live.estimatedRoomFee(),
          liveTotal(order, live, sums),
          session.openedAt(), session.closedAt()));
    }
    return views;
  }

  /**
   * 实时应付合计（最小货币单位）：会话开台中/挂单中且算得出实时房费时用「差额法」；
   * 否则库内合计即终值。结果兜底不为负。
   */
  private static BigDecimal liveTotal(Order order, KtvSessionApplicationService.LiveEstimate live,
                                      KtvSessionApplicationService.OrderAmountSums sums) {
    long basisMinor = OrderAmountApplicationService.totalBasisMinor(order, sums.activeItemsMinor());
    if (live == null) {
      return BigDecimal.valueOf(basisMinor);
    }
    return BigDecimal.valueOf(OrderAmountApplicationService.withRoomFeeReplaced(
        basisMinor, sums.roomFeeItemsMinor(), live.estimatedRoomFee()));
  }

  /** 订单列表的可见范围：商户权限码优先（null = 不限）；消费者会话收窄到本人；无上下文保持既有行为。 */
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
    Long memberId = memberLookupProvider == null || tenantId == null || accountId == null
        ? null
        : memberLookupProvider.findMemberId(tenantId, accountId).orElse(null);
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

  /** 业态以签名租户上下文中的门店权威快照为准；请求体字段仅保留为旧客户端的输入兼容。 */
  private static String effectiveBusinessType(TenantContext context, String requested) {
    String authoritative = context == null ? null : context.businessType();
    if (authoritative != null && !authoritative.isBlank()) {
      if (requested != null && !requested.isBlank() && !authoritative.equalsIgnoreCase(requested)) {
        throw new ApiException(422, "BUSINESS_TYPE_MISMATCH", "请求业态与门店当前业态不一致");
      }
      return authoritative;
    }
    if (requested == null || requested.isBlank()) {
      throw new ApiException(422, "BUSINESS_TYPE_CONTEXT_MISSING", "门店上下文缺少业态信息，请重新选择门店");
    }
    return requested;
  }

  /** 幂等键查询（租户 + 键唯一）：键为空直接返回 null（不带头的调用方保持既有行为）。 */
  private Order findByIdempotencyKey(Long tenantId, String idempotencyKey) {
    String key = blankToNull(idempotencyKey);
    if (key == null || tenantId == null) {
      return null;
    }
    return orderRepository.findByIdempotencyKey(tenantId, key).orElse(null);
  }

  /**
   * 回放已有订单时补上会话 id。用 {@code listByOrderIds} 而不是 {@code findByOrderId}：
   * 后者在没有会话时抛 404，非 KTV 单/未选包厢的单本来就没有会话。
   */
  private OrderView withSession(Order order) {
    OrderView view = OrderView.from(order);
    if (view == null || view.id() == null) {
      return view;
    }
    KtvSession session = ktvSessionService.listByOrderIds(List.of(view.id())).get(view.id());
    return session == null ? view : view.withSessionId(session.id());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /**
   * 开台币种：显式传入时必须是受支持币种，否则 400；未传/空白时取当时租户币种；
   * 写入的就是订单的币种快照，之后租户改设置不得改写它。
   */
  private static String resolveCurrencyCode(String requested) {
    if (requested != null && !requested.isBlank() && !Currency.isSupported(requested)) {
      throw new ApiException(400, "CURRENCY_UNSUPPORTED", "不支持的币种: " + requested);
    }
    return requested == null || requested.isBlank()
        ? CurrencyResolver.currentCode()
        : Currency.parse(requested).code();
  }

  /** 挂单/解挂失败留痕：动作码与成功路径同码，FAILURE + 稳定 errorCode。 */
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

  private Order requireOrder(Long id) {
    return orderCancellationService.requireOrder(id);
  }

  /** 是否存在开台中/挂单中的 KTV 会话（挂单是会话内的操作标记，不是订单交易状态）。 */
  private boolean hasOngoingSession(Long orderId) {
    KtvSession session = ktvSessionService.listByOrderIds(List.of(orderId)).get(orderId);
    return session != null && session.ongoing();
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

  /** 创建订单命令（与 HTTP 请求解耦）。 */
  public record CreateOrderCommand(Long tenantId, Long organizationId, Long storeId, String businessType,
                                   String currencyCode, Long resourceId) {
  }
}
