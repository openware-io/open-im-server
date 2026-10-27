package io.openware.platform.order.application;

import io.openware.common.exception.BusinessException;
import io.openware.platform.order.application.dto.BillResult;
import io.openware.platform.order.application.dto.OrderView;
import io.openware.platform.order.domain.ktv.model.KtvPricingPlan;
import io.openware.platform.order.domain.ktv.model.KtvRoundingDirection;
import io.openware.platform.order.domain.ktv.model.KtvServerSession;
import io.openware.platform.order.domain.ktv.model.KtvSession;
import io.openware.platform.order.domain.ktv.repository.KtvServerSessionRepository;
import io.openware.platform.order.domain.ktv.repository.KtvSessionRepository;
import io.openware.platform.order.domain.ktv.repository.KtvPricingPlanProvider;
import io.openware.platform.order.domain.ktv.service.KtvRoomFeeCalculator;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.OrderItem;
import io.openware.platform.order.domain.order.repository.OrderItemQuery;
import io.openware.platform.order.domain.order.repository.OrderItemRepository;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.repository.Persisted;
import io.openware.platform.order.infra.client.PaymentCollectedClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * 客户消费账单（只读）：GET /business/orders/{id}/bill。
 * 金额全部服务端计算并返回最小货币单位整数，客户端不传金额、不自行计算税费/折扣/抵扣/找零。
 *
 * <p><b>单一事实来源</b>：本服务不自己实现任何计费公式，全部委托同一处：
 * <ul>
 *   <li>挑「本次开台」的会话 → {@link KtvSessionApplicationService#currentSessionOf(List)}
 *       （与订单投影/收银台看板同一条，绝不用 findFirst）；</li>
 *   <li>实时房费（含 PAUSED 停表） → {@link KtvSessionApplicationService#liveRoomFee}
 *       （与看板实时估算同一实现）；</li>
 *   <li>合计基数与差额法 → {@link OrderAmountApplicationService#totalBasisMinor} /
 *       {@link OrderAmountApplicationService#withRoomFeeReplaced}
 *       （与 {@code OrderController#liveTotal} 同一实现，两边对同一张单必然同值）。</li>
 * </ul>
 */
@Service
@Slf4j
public class BillApplicationService {
    /** 包厢费行金额来源：开台中实时估算（随计费时长增长）。 */
    static final String SOURCE_LIVE = "LIVE";
    /** 包厢费行金额来源：结台固化（closed_at 已知，时长可信）。 */
    static final String SOURCE_CLOSED = "CLOSED";
    /** 包厢费行金额来源：历史固化明细，会话没有结台时刻 → 时长不可知，只能按块数 × 单价自证。 */
    static final String SOURCE_HISTORICAL = "HISTORICAL";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final KtvSessionRepository ktvSessionRepository;
    private final KtvServerSessionRepository serverSessionRepository;
    private final KtvPricingPlanProvider pricingPlanProvider;
    private final PaymentCollectedClient paymentCollectedClient;

    public BillApplicationService(OrderRepository orderRepository,
                                  OrderItemRepository orderItemRepository,
                                  KtvSessionRepository ktvSessionRepository,
                                  KtvServerSessionRepository serverSessionRepository,
                                  KtvPricingPlanProvider pricingPlanProvider,
                                  PaymentCollectedClient paymentCollectedClient) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.ktvSessionRepository = ktvSessionRepository;
        this.serverSessionRepository = serverSessionRepository;
        this.pricingPlanProvider = pricingPlanProvider;
        this.paymentCollectedClient = paymentCollectedClient;
    }

    public BillResult buildBill(Long orderId) {
        Order order = requireOrder(orderId);
        // 明细的 updated_at 是房费金额的「固化时刻」（账单里要展示），因此读回行 + 记账快照。
        List<Persisted<OrderItem>> itemRows = queryItems(orderId);
        List<OrderItem> items = itemRows.stream().map(Persisted::aggregate).toList();
        List<KtvSession> sessions = querySessions(orderId);
        List<KtvServerSession> servers = queryServerSessions(orderId);

        // 与订单投影同一条挑选规则：多会话订单必须两边取「当前这一次」，否则包厢费一个按本次开台实时算、
        // 一个按上一次已结台会话的固化值展示。
        KtvSession current = KtvSessionApplicationService.currentSessionOf(sessions);
        BillResult.RoomFee roomFee = buildRoomFee(order, current, items, roomFeeFixedAt(itemRows));
        List<BillResult.ServerLine> serverLines = buildServerLines(servers);
        Set<Long> serverSessionResourceIds = servers.stream()
                .filter(s -> "ENDED".equals(s.status()))
                .map(KtvServerSession::serverResourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<BillResult.ItemLine> itemLines = buildItemLines(items, serverSessionResourceIds);
        List<BillResult.PromotionLine> promotions = buildPromotions(order);

        long roomFeeItemsMinor = roomFeeItemsMinor(items);
        // 合计基数与差额法都与订单投影共用（OrderAmountApplicationService），
        // 「账单 totalAmount == 看板 liveTotalAmount」因此是构造性成立，而不是两处各算一遍碰巧相等。
        long totalMinor = OrderAmountApplicationService.totalBasisMinor(order, sumItemsMinor(items));
        if (roomFee != null) {
            // 展示的房费（开台中＝实时值；已结台＝固化明细值）替换掉合计里那份库内房费快照：
            // 开台中明细是「上次刷新」的快照，直接原样展示会少收/多收；已结台时两者本就相等，是恒等变换。
            // 没有房费行（PACKAGE 等）时不做替换：不能凭空把库内房费从合计里扣掉。
            totalMinor = OrderAmountApplicationService.withRoomFeeReplaced(
                    totalMinor, roomFeeItemsMinor, roomFee.amount());
        }
        long paidMinor = toMinor(order.paidAmount());
        long payableMinor = Math.max(0L, totalMinor - paidMinor);
        BillResult.Collected collected = buildCollected(order, orderId, paidMinor);
        long change = Math.max(0L, collected.cash() - totalMinor);
        // 原价合计 = 各展示分区之和（客户端不再自己把行加起来）；合计 = 原价合计 − 优惠 + 税 恒成立。
        long subtotalMinor = (roomFee == null ? 0L : roomFee.amount())
                + itemLines.stream().mapToLong(BillResult.ItemLine::amount).sum()
                + serverLines.stream().mapToLong(BillResult.ServerLine::amount).sum();
        long discountMinor = promotions.stream().mapToLong(BillResult.PromotionLine::amount).sum();

        return new BillResult(order.status(), order.trade().name(),
                current == null ? null : current.state().name(),
                OrderView.settlementStatusOf(order.trade(), order.refund()),
                order.currencyCode(), roomFee, itemLines, serverLines,
                promotions, subtotalMinor, discountMinor, toMinor(order.taxAmount()),
                totalMinor, paidMinor, payableMinor, collected, change);
    }

    /**
     * 房费明细的「固化时刻」（{@code updated_at}）：记账列不进领域模型，因此由适配器随行读回，
     * 这里取第一行房费明细的快照值传给账单（历史固化口径下要展示「这笔金额是什么时候定的」）。
     */
    private static LocalDateTime roomFeeFixedAt(List<Persisted<OrderItem>> itemRows) {
        for (Persisted<OrderItem> row : itemRows) {
            if ("ROOM_FEE".equals(row.aggregate().itemType())) {
                return row.audit() == null ? null : row.audit().updatedAt();
            }
        }
        return null;
    }

    /**
     * 包厢费分区（账单里「包厢费」这一行的全部信息，含计费口径说明）。
     *
     * <p>三种情况，口径与看板/结台严格对齐：
     * <ol>
     *   <li>当前会话开台中（OPEN/PAUSED）：按**此刻结台**实时算（PAUSED 停表在 pause_started_at），
     *       与 {@code KtvSessionApplicationService#liveEstimate}（收银台/订单列表用）同一个
     *       {@code liveRoomFee}。
     *       计划不按时长计费（PACKAGE 一口价）→ 无房费行（不造 0 元假行）；</li>
     *   <li>已结台（CLOSED）或已终结：直接取**固化的 ROOM_FEE 明细**（结台时写死，改规则不影响历史账单）；
     *       明细缺失（历史脏数据）时也不回退复算——已终结订单的库内合计就是权威金额，
     *       凭空补一条房费行只会让「明细之和 ≠ 合计」，钱并不会因此多收；</li>
     *   <li>没有会话：同样只在有固化明细时展示（非 KTV 单没有房费行）。</li>
     * </ol>
     */
    private BillResult.RoomFee buildRoomFee(Order order, KtvSession current, List<OrderItem> items,
                                            LocalDateTime roomFeeFixedAt) {
        List<OrderItem> roomItems = roomFeeItems(items);
        if (isOpenSession(current)) {
            KtvPricingPlan plan = planFromSnapshotOrNull(current.billingRuleSnapshotJson());
            if (plan == null) {
                plan = pricingPlanProvider.resolve(order.tenantId(), order.storeId());
            }
            LocalDateTime now = LocalDateTime.now();
            KtvRoomFeeCalculator.Fee fee = KtvSessionApplicationService.liveRoomFee(current, plan, now);
            if (fee == null) {
                // 一口价套餐不按时长计费（房费明细也不落库，见 upsertRoomFeeItem 的 PACKAGE 特判）：
                // 这里不能造一条 0 元房费行，否则账单凭空多一行「包厢费 0」。
                return null;
            }
            LocalDateTime endAt = KtvSessionApplicationService.billableEndAt(current, now);
            int paused = pausedSeconds(current);
            // fee != null 已蕴含 plan 按时长计费（liveRoomFee 对非时长方案返回 null），此处 plan 必非空。
            return new BillResult.RoomFee(roomFeeName(roomItems, plan),
                    current.billingStartAt() == null ? null : current.billingStartAt().toString(),
                    null,
                    fee.unitPriceMinor(),
                    KtvRoomFeeCalculator.billableSeconds(current.billingStartAt(), endAt, paused),
                    fee.amountMinor(),
                    fee.units(), paused,
                    KtvRoomFeeCalculator.standardSeconds(current.billingStartAt(), current.reservedEndAt(),
                            plan.defaultSessionMinutes()),
                    fee.inSeconds(), fee.overSeconds(),
                    plan.effectiveIncrementMinutes(),
                    plan.effectiveOvertimeRate().toPlainString(),
                    plan.billingUnit() == null ? null : plan.billingUnit().name(),
                    KtvSessionApplicationService.planDisplayName(plan),
                    plan.roomUnitPrice(),
                    plan.serverUnitPrice(),
                    plan.roomFeeIncludesServer(),
                    true, SOURCE_LIVE, true, null);
        }
        if (roomItems.isEmpty()) {
            return null;
        }
        // 已固化：金额/单价/块数全部取明细快照（历史脏数据多行时一并求和，与订单合计的重算口径一致）。
        long amountMinor = roomItems.stream().mapToLong(i -> toMinor(i.totalAmount())).sum();
        long quantity = roomItems.stream().mapToLong(i -> toMinor(i.quantity())).sum();
        OrderItem first = roomItems.getFirst();
        KtvPricingPlan plan = frozenPlan(current, roomItems);
        int paused = pausedSeconds(current);
        LocalDateTime start = current != null && current.billingStartAt() != null
                ? current.billingStartAt() : null;
        LocalDateTime end = current != null ? current.closedAt() : null;
        // 结台时刻缺失（订单 72 那类：会话 CANCELLED 且 closed_at IS NULL，或订单上只剩历史房费明细）时，
        // **时长不可知**：此前账单会输出「0 分钟 + ¥40」这种自相矛盾的行，用户没法解释这笔钱。
        // 这里改为明确标注来源为历史固化值，并把块数/单价/固化时刻下发，页面据此自证金额。
        boolean durationKnown = end != null;
        String source = durationKnown ? SOURCE_CLOSED : SOURCE_HISTORICAL;
        return new BillResult.RoomFee(roomFeeName(roomItems, plan),
                start == null ? null : start.toString(),
                end == null ? null : end.toString(),
                toMinor(first.unitPrice()),
                durationKnown ? KtvRoomFeeCalculator.billableSeconds(start, end, paused) : 0L,
                amountMinor,
                quantity, paused,
                plan == null ? 0L : KtvRoomFeeCalculator.standardSeconds(start,
                        current == null ? null : current.reservedEndAt(),
                        plan.defaultSessionMinutes()),
                0L, 0L,
                plan == null ? 0 : plan.effectiveIncrementMinutes(),
                plan == null ? null : plan.effectiveOvertimeRate().toPlainString(),
                plan == null || plan.billingUnit() == null ? null : plan.billingUnit().name(),
                KtvSessionApplicationService.planDisplayName(plan),
                plan == null ? 0L : plan.roomUnitPrice(),
                plan == null ? 0L : plan.serverUnitPrice(),
                plan != null && plan.roomFeeIncludesServer(),
                false, source, durationKnown,
                roomFeeFixedAt == null ? null : roomFeeFixedAt.toString());
    }

    /**
     * 固化房费行使用的计价方案：明细自带的 price_snapshot_json 最权威（结台时写的就是它），
     * 其次会话的规则快照，最后才是门店当前方案（仅用于把「单价怎么来的」讲清楚，金额不参与计算）。
     *
     * <p>解析失败一律降级为 {@code null}（本方法只服务账单展示）：读路径绝不能让一份脏快照把整张账单打成 500，
     * 而写路径（结台/刷新）仍走严格的 {@code planFromSnapshot}（快照坏了就不许结台）。
     */
    private KtvPricingPlan frozenPlan(KtvSession current, List<OrderItem> roomItems) {
        KtvPricingPlan plan = planFromSnapshotOrNull(roomItems.getFirst().priceSnapshotJson());
        if (plan == null && current != null) {
            plan = planFromSnapshotOrNull(current.billingRuleSnapshotJson());
        }
        return plan;
    }

    /** 读路径的快照解析：无效快照返回 null（调用方回退），不抛业务异常（与写路径的严格口径区分）。 */
    private static KtvPricingPlan planFromSnapshotOrNull(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return null;
        }
        try {
            return KtvSessionApplicationService.planFromSnapshot(snapshot);
        } catch (RuntimeException invalidSnapshot) {
            log.debug("bill figure could not be resolved", invalidSnapshot);
            return null;
        }
    }

    /**
     * 包厢费分区名称：优先取结台固化的明细名（历史账单保持「包厢计时费」），
     * 明细缺失（回退复算）时按同一口径由计价方案推导，前端不硬编码。
     */
    private static String roomFeeName(List<OrderItem> roomItems, KtvPricingPlan plan) {
        if (roomItems != null && !roomItems.isEmpty() && roomItems.getFirst().nameSnapshot() != null) {
            return roomItems.getFirst().nameSnapshot();
        }
        return plan == null ? "包厢费" : KtvSessionApplicationService.roomFeeItemName(plan);
    }

    /**
     * 加项明细：完整账单必须包含所有消费类明细（商品/加项/服务目录项，如「服务员点歌」）。
     * 只有两类不进本列表，避免与其它分区重复计数：
     * 1) ROOM_FEE —— 已由 {@link BillResult#roomFee()} 单独展示；
     * 2) 服务人员计时费（item_type=SERVICE 且 resourceId 命中某个服务人员会话）—— 由 {@link BillResult#servers()} 单独展示。
     * 于是 sum(items) + sum(servers) + roomFee == totalAmount 恒成立（见 BillApplicationServiceTest）。
     */
    private List<BillResult.ItemLine> buildItemLines(List<OrderItem> items, Set<Long> serverSessionResourceIds) {
        return items.stream()
                .filter(i -> !"ROOM_FEE".equals(i.itemType()))
                .filter(i -> !isServerSessionFeeItem(i, serverSessionResourceIds))
                .map(i -> new BillResult.ItemLine(
                        i.nameSnapshot(),
                        toMinor(i.unitPrice()),
                        i.quantity(),
                        toMinor(i.totalAmount())))
                .toList();
    }

    /**
     * 该明细是否为服务人员计时费：由 {@code KtvServerSessionApplicationService.end} 写入，
     * 特征是 SERVICE + resourceId=服务人员资源 + price_snapshot_json（加项路径不写快照）。
     * 三个条件同时成立才算，避免把「服务员点歌」这类同样是 SERVICE、且恰好带了 resourceId 的
     * 加项明细误并从 items 里剔除（那会让 sum(items)+sum(servers) 少算这笔钱）。
     */
    private static boolean isServerSessionFeeItem(OrderItem item, Set<Long> serverSessionResourceIds) {
        return "SERVICE".equals(item.itemType())
                && item.resourceId() != null
                && item.priceSnapshotJson() != null
                && serverSessionResourceIds.contains(item.resourceId());
    }

    /**
     * 服务人员分区：包厢费已含 1 名标准服务人员，因此**按创建顺序第一个未取消**的服务人员会话
     * 金额为 0（{@code KtvServerSessionApplicationService.end} 已按同一判定零收），标签标注免收原因；
     * 第 2 名起按各自 {@code price_per_inc} 全价展示。判定与计费共用
     * {@link KtvServerSessionApplicationService#freeServerSessionIds}，两边永远一致。
     *
     * <p>{@code quantity} 用会话固化的递增粒度/舍入方向按同一算法换算块数（与明细里的数量同源），
     * 供页面展示「单价 × 块数」，不需要客户端再算一遍。
     */
    private List<BillResult.ServerLine> buildServerLines(List<KtvServerSession> servers) {
        java.util.Set<Long> freeIds = KtvServerSessionApplicationService.freeServerSessionIds(servers);
        return servers.stream()
                .filter(s -> "ENDED".equals(s.status()))
                .map(s -> new BillResult.ServerLine(
                        serverLineLabel(s, freeIds.contains(s.id())),
                        s.durationSeconds() == null ? 0L : s.durationSeconds(),
                        s.pricePerInc() == null ? 0L : s.pricePerInc(),
                        toMinor(s.totalAmount()),
                        UnitTimeFeeCalculator.calculateBlocks(
                                s.durationSeconds() == null ? 0L : s.durationSeconds(),
                                s.incrementMinutes() == null ? 0 : s.incrementMinutes(),
                                KtvRoundingDirection.fromCode(s.roundingDirection()))))
                .toList();
    }

    /** 服务人员行标签：免费名额标注「含 1 名标准服务人员，不另计费」，其余为「额外服务人员」。 */
    private static String serverLineLabel(KtvServerSession s, boolean freeQuota) {
        return freeQuota
                ? "服务人员#" + s.serverResourceId() + "（含 1 名标准服务人员，不另计费）"
                : "额外服务人员#" + s.serverResourceId();
    }

    private List<BillResult.PromotionLine> buildPromotions(Order order) {
        List<BillResult.PromotionLine> lines = new ArrayList<>();
        long discount = toMinor(order.discountAmount());
        if (discount > 0) {
            lines.add(new BillResult.PromotionLine("DISCOUNT", discount));
        }
        // 优惠逐项：首发仅整单折扣；券/满减/会员价在接入 mkt_* 后按类型拆分（KTV_BUSINESS_01 §6）。
        return lines;
    }

    /** 是否「开台中」（OPEN/PAUSED）：只有这两个状态的房费还在随时间增长、账单要按实时值展示。 */
    private static boolean isOpenSession(KtvSession session) {
        return session != null && session.ongoing();
    }

    /** 全部 ACTIVE 明细合计（差额法回退基数用；与订单重算/投影同一口径）。 */
    private long sumItemsMinor(List<OrderItem> items) {
        return items.stream().mapToLong(i -> toMinor(i.totalAmount())).sum();
    }

    /** 订单的房费明细行（ACTIVE 已在 queryItems 收口）。 */
    private static List<OrderItem> roomFeeItems(List<OrderItem> items) {
        return items.stream().filter(i -> "ROOM_FEE".equals(i.itemType())).toList();
    }

    /**
     * 明细里的房费快照合计（开台中/回退展示时用它换出展示口径的房费；历史仅一行，多行也一并合计）。
     * 与 {@code KtvSessionApplicationService#orderAmountSumsByOrderIds}、订单重算的求和口径一致。
     */
    private long roomFeeItemsMinor(List<OrderItem> items) {
        return roomFeeItems(items).stream().mapToLong(i -> toMinor(i.totalAmount())).sum();
    }

    /** 会话已累计的暂停秒数（null 视为 0）。 */
    private static int pausedSeconds(KtvSession session) {
        return session == null || session.pausedSeconds() == null ? 0 : session.pausedSeconds();
    }

    /**
     * 已收分项：优先取 payment 域按订单汇总的组合收款分腿（现金/储值/积分）；
     * 读不到（payment 不可达或上下文缺失）时退回「已收合计记现金」的兜底口径。
     */
    private BillResult.Collected buildCollected(Order order, Long orderId, long paidMinor) {
        if (paymentCollectedClient != null) {
            var breakdown = paymentCollectedClient.collected(orderId);
            if (breakdown.isPresent()) {
                var value = breakdown.get();
                return new BillResult.Collected(value.cash(), value.wallet(), value.points());
            }
        }
        return new BillResult.Collected(paidMinor, 0L, 0L);
    }

    /** 金额 BigDecimal → 最小货币单位 long。订单域金额已统一按「分」存储，此处仅做类型收敛，不做分/元换算。 */
    private long toMinor(BigDecimal amount) {
        if (amount == null) {
            return 0L;
        }
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private Order requireOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "订单不存在"));
    }

    /** 账单只计入已生效（ACTIVE）明细；按 id 升序与改造前的排序一致。读回行 + 记账快照（房费固化时刻）。 */
    private List<Persisted<OrderItem>> queryItems(Long orderId) {
        return orderItemRepository.list(OrderItemQuery.activeOfOrder(orderId));
    }

    private List<KtvSession> querySessions(Long orderId) {
        return ktvSessionRepository.listByOrderId(orderId);
    }

    private List<KtvServerSession> queryServerSessions(Long orderId) {
        return serverSessionRepository.listByOrderId(orderId);
    }
}
