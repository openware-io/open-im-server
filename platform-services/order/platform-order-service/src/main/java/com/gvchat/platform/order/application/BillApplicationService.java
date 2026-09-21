package com.gvchat.platform.order.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.platform.order.application.dto.BillResult;
import com.gvchat.platform.order.domain.ktv.model.KtvPricingPlan;
import com.gvchat.platform.order.domain.ktv.model.KtvRoundingDirection;
import com.gvchat.platform.order.domain.ktv.port.KtvPricingPlanProvider;
import com.gvchat.platform.order.domain.ktv.service.KtvRoomFeeCalculator;
import com.gvchat.platform.order.infra.persistence.mapper.KtvServerSessionMapper;
import com.gvchat.platform.order.infra.persistence.mapper.KtvSessionMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.po.KtvServerSessionPo;
import com.gvchat.platform.order.infra.persistence.po.KtvSessionPo;
import com.gvchat.platform.order.infra.persistence.po.OrderItemPo;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import com.gvchat.platform.order.infra.client.PaymentCollectedClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
public class BillApplicationService {
    /** 包厢费行金额来源：开台中实时估算（随计费时长增长）。 */
    static final String SOURCE_LIVE = "LIVE";
    /** 包厢费行金额来源：结台固化（closed_at 已知，时长可信）。 */
    static final String SOURCE_CLOSED = "CLOSED";
    /** 包厢费行金额来源：历史固化明细，会话没有结台时刻 → 时长不可知，只能按块数 × 单价自证。 */
    static final String SOURCE_HISTORICAL = "HISTORICAL";

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final KtvSessionMapper ktvSessionMapper;
    private final KtvServerSessionMapper serverSessionMapper;
    private final KtvPricingPlanProvider pricingPlanProvider;
    private final PaymentCollectedClient paymentCollectedClient;

    public BillApplicationService(OrderMapper orderMapper,
                                  OrderItemMapper orderItemMapper,
                                  KtvSessionMapper ktvSessionMapper,
                                  KtvServerSessionMapper serverSessionMapper,
                                  KtvPricingPlanProvider pricingPlanProvider,
                                  PaymentCollectedClient paymentCollectedClient) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.ktvSessionMapper = ktvSessionMapper;
        this.serverSessionMapper = serverSessionMapper;
        this.pricingPlanProvider = pricingPlanProvider;
        this.paymentCollectedClient = paymentCollectedClient;
    }

    public BillResult buildBill(Long orderId) {
        OrderPo order = requireOrder(orderId);
        List<OrderItemPo> items = queryItems(orderId);
        List<KtvSessionPo> sessions = querySessions(orderId);
        List<KtvServerSessionPo> servers = queryServerSessions(orderId);

        // 与订单投影同一条挑选规则：多会话订单必须两边取「当前这一次」，否则包厢费一个按本次开台实时算、
        // 一个按上一次已结台会话的固化值展示。
        KtvSessionPo current = KtvSessionApplicationService.currentSessionOf(sessions);
        BillResult.RoomFee roomFee = buildRoomFee(order, current, items);
        List<BillResult.ServerLine> serverLines = buildServerLines(servers);
        Set<Long> serverSessionResourceIds = servers.stream()
                .filter(s -> "ENDED".equals(s.getStatus()))
                .map(KtvServerSessionPo::getServerResourceId)
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
        long paidMinor = toMinor(order.getPaidAmount());
        long payableMinor = Math.max(0L, totalMinor - paidMinor);
        BillResult.Collected collected = buildCollected(order, orderId, paidMinor);
        long change = Math.max(0L, collected.cash() - totalMinor);
        // 原价合计 = 各展示分区之和（客户端不再自己把行加起来）；合计 = 原价合计 − 优惠 + 税 恒成立。
        long subtotalMinor = (roomFee == null ? 0L : roomFee.amount())
                + itemLines.stream().mapToLong(BillResult.ItemLine::amount).sum()
                + serverLines.stream().mapToLong(BillResult.ServerLine::amount).sum();
        long discountMinor = promotions.stream().mapToLong(BillResult.PromotionLine::amount).sum();

        return new BillResult(order.getStatus(), order.getCurrencyCode(), roomFee, itemLines, serverLines,
                promotions, subtotalMinor, discountMinor, toMinor(order.getTaxAmount()),
                totalMinor, paidMinor, payableMinor, collected, change);
    }

    /**
     * 包厢费分区（账单里「包厢费」这一行的全部信息，含计费口径说明）。
     *
     * <p>三种情况，口径与看板/结台严格对齐：
     * <ol>
     *   <li>当前会话开台中（OPEN/PAUSED）：按**此刻结台**实时算（PAUSED 停表在 pause_started_at），
     *       与 {@code fillLiveEstimate}（收银台/订单列表用）同一个 {@code liveRoomFee}。
     *       计划不按时长计费（PACKAGE 一口价）→ 无房费行（不造 0 元假行）；</li>
     *   <li>已结台（CLOSED）或已终结：直接取**固化的 ROOM_FEE 明细**（结台时写死，改规则不影响历史账单）；
     *       明细缺失（历史脏数据）时也不回退复算——已终结订单的库内合计就是权威金额，
     *       凭空补一条房费行只会让「明细之和 ≠ 合计」，钱并不会因此多收；</li>
     *   <li>没有会话：同样只在有固化明细时展示（非 KTV 单没有房费行）。</li>
     * </ol>
     */
    private BillResult.RoomFee buildRoomFee(OrderPo order, KtvSessionPo current, List<OrderItemPo> items) {
        List<OrderItemPo> roomItems = roomFeeItems(items);
        if (isOpenSession(current)) {
            KtvPricingPlan plan = planFromSnapshotOrNull(current.getBillingRuleSnapshotJson());
            if (plan == null) {
                plan = pricingPlanProvider.resolve(order.getTenantId(), order.getStoreId());
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
                    current.getBillingStartAt() == null ? null : current.getBillingStartAt().toString(),
                    null,
                    fee.unitPriceMinor(),
                    KtvRoomFeeCalculator.billableSeconds(current.getBillingStartAt(), endAt, paused),
                    fee.amountMinor(),
                    fee.units(), paused,
                    KtvRoomFeeCalculator.standardSeconds(current.getBillingStartAt(), current.getReservedEndAt(),
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
        long amountMinor = roomItems.stream().mapToLong(i -> toMinor(i.getTotalAmount())).sum();
        long quantity = roomItems.stream().mapToLong(i -> toMinor(i.getQuantity())).sum();
        OrderItemPo first = roomItems.getFirst();
        KtvPricingPlan plan = frozenPlan(current, roomItems);
        int paused = pausedSeconds(current);
        LocalDateTime start = current != null && current.getBillingStartAt() != null
                ? current.getBillingStartAt() : null;
        LocalDateTime end = current != null ? current.getClosedAt() : null;
        // 结台时刻缺失（订单 72 那类：会话 CANCELLED 且 closed_at IS NULL，或订单上只剩历史房费明细）时，
        // **时长不可知**：此前账单会输出「0 分钟 + ¥40」这种自相矛盾的行，用户没法解释这笔钱。
        // 这里改为明确标注来源为历史固化值，并把块数/单价/固化时刻下发，页面据此自证金额。
        boolean durationKnown = end != null;
        String source = durationKnown ? SOURCE_CLOSED : SOURCE_HISTORICAL;
        return new BillResult.RoomFee(roomFeeName(roomItems, plan),
                start == null ? null : start.toString(),
                end == null ? null : end.toString(),
                toMinor(first.getUnitPrice()),
                durationKnown ? KtvRoomFeeCalculator.billableSeconds(start, end, paused) : 0L,
                amountMinor,
                quantity, paused,
                plan == null ? 0L : KtvRoomFeeCalculator.standardSeconds(start,
                        current == null ? null : current.getReservedEndAt(),
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
                first.getUpdatedAt() == null ? null : first.getUpdatedAt().toString());
    }

    /**
     * 固化房费行使用的计价方案：明细自带的 price_snapshot_json 最权威（结台时写的就是它），
     * 其次会话的规则快照，最后才是门店当前方案（仅用于把「单价怎么来的」讲清楚，金额不参与计算）。
     *
     * <p>解析失败一律降级为 {@code null}（本方法只服务账单展示）：读路径绝不能让一份脏快照把整张账单打成 500，
     * 而写路径（结台/刷新）仍走严格的 {@code planFromSnapshot}（快照坏了就不许结台）。
     */
    private KtvPricingPlan frozenPlan(KtvSessionPo current, List<OrderItemPo> roomItems) {
        KtvPricingPlan plan = planFromSnapshotOrNull(roomItems.getFirst().getPriceSnapshotJson());
        if (plan == null && current != null) {
            plan = planFromSnapshotOrNull(current.getBillingRuleSnapshotJson());
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
            return null;
        }
    }

    /**
     * 包厢费分区名称：优先取结台固化的明细名（历史账单保持「包厢计时费」），
     * 明细缺失（回退复算）时按同一口径由计价方案推导，前端不硬编码。
     */
    private static String roomFeeName(List<OrderItemPo> roomItems, KtvPricingPlan plan) {
        if (roomItems != null && !roomItems.isEmpty() && roomItems.getFirst().getNameSnapshot() != null) {
            return roomItems.getFirst().getNameSnapshot();
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
    private List<BillResult.ItemLine> buildItemLines(List<OrderItemPo> items, Set<Long> serverSessionResourceIds) {
        return items.stream()
                .filter(i -> !"ROOM_FEE".equals(i.getItemType()))
                .filter(i -> !isServerSessionFeeItem(i, serverSessionResourceIds))
                .map(i -> new BillResult.ItemLine(
                        i.getNameSnapshot(),
                        toMinor(i.getUnitPrice()),
                        i.getQuantity(),
                        toMinor(i.getTotalAmount())))
                .toList();
    }

    /**
     * 该明细是否为服务人员计时费：由 {@code KtvServerSessionApplicationService.end} 写入，
     * 特征是 SERVICE + resourceId=服务人员资源 + price_snapshot_json（加项路径不写快照）。
     * 三个条件同时成立才算，避免把「服务员点歌」这类同样是 SERVICE、且恰好带了 resourceId 的
     * 加项明细误并从 items 里剔除（那会让 sum(items)+sum(servers) 少算这笔钱）。
     */
    private static boolean isServerSessionFeeItem(OrderItemPo item, Set<Long> serverSessionResourceIds) {
        return "SERVICE".equals(item.getItemType())
                && item.getResourceId() != null
                && item.getPriceSnapshotJson() != null
                && serverSessionResourceIds.contains(item.getResourceId());
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
    private List<BillResult.ServerLine> buildServerLines(List<KtvServerSessionPo> servers) {
        java.util.Set<Long> freeIds = KtvServerSessionApplicationService.freeServerSessionIds(servers);
        return servers.stream()
                .filter(s -> "ENDED".equals(s.getStatus()))
                .map(s -> new BillResult.ServerLine(
                        serverLineLabel(s, freeIds.contains(s.getId())),
                        s.getDurationSeconds() == null ? 0L : s.getDurationSeconds(),
                        s.getPricePerInc() == null ? 0L : s.getPricePerInc(),
                        toMinor(s.getTotalAmount()),
                        UnitTimeFeeCalculator.calculateBlocks(
                                s.getDurationSeconds() == null ? 0L : s.getDurationSeconds(),
                                s.getIncrementMinutes() == null ? 0 : s.getIncrementMinutes(),
                                KtvRoundingDirection.fromCode(s.getRoundingDirection()))))
                .toList();
    }

    /** 服务人员行标签：免费名额标注「含 1 名标准服务人员，不另计费」，其余为「额外服务人员」。 */
    private static String serverLineLabel(KtvServerSessionPo s, boolean freeQuota) {
        return freeQuota
                ? "服务人员#" + s.getServerResourceId() + "（含 1 名标准服务人员，不另计费）"
                : "额外服务人员#" + s.getServerResourceId();
    }

    private List<BillResult.PromotionLine> buildPromotions(OrderPo order) {
        List<BillResult.PromotionLine> lines = new ArrayList<>();
        long discount = toMinor(order.getDiscountAmount());
        if (discount > 0) {
            lines.add(new BillResult.PromotionLine("DISCOUNT", discount));
        }
        // 优惠逐项：首发仅整单折扣；券/满减/会员价在接入 mkt_* 后按类型拆分（KTV_BUSINESS_01 §6）。
        return lines;
    }

    /** 是否「开台中」（OPEN/PAUSED）：只有这两个状态的房费还在随时间增长、账单要按实时值展示。 */
    private static boolean isOpenSession(KtvSessionPo session) {
        return session != null && ("OPEN".equals(session.getStatus()) || "PAUSED".equals(session.getStatus()));
    }

    /** 全部 ACTIVE 明细合计（差额法回退基数用；与订单重算/投影同一口径）。 */
    private long sumItemsMinor(List<OrderItemPo> items) {
        return items.stream().mapToLong(i -> toMinor(i.getTotalAmount())).sum();
    }

    /** 订单的房费明细行（ACTIVE 已在 queryItems 收口）。 */
    private static List<OrderItemPo> roomFeeItems(List<OrderItemPo> items) {
        return items.stream().filter(i -> "ROOM_FEE".equals(i.getItemType())).toList();
    }

    /**
     * 明细里的房费快照合计（开台中/回退展示时用它换出展示口径的房费；历史仅一行，多行也一并合计）。
     * 与 {@code KtvSessionApplicationService#orderAmountSumsByOrderIds}、订单重算的求和口径一致。
     */
    private long roomFeeItemsMinor(List<OrderItemPo> items) {
        return roomFeeItems(items).stream().mapToLong(i -> toMinor(i.getTotalAmount())).sum();
    }

    /** 会话已累计的暂停秒数（null 视为 0）。 */
    private static int pausedSeconds(KtvSessionPo session) {
        return session == null || session.getPausedSeconds() == null ? 0 : session.getPausedSeconds();
    }

    /**
     * 已收分项：优先取 payment 域按订单汇总的组合收款分腿（现金/储值/积分）；
     * 读不到（payment 不可达或上下文缺失）时退回「已收合计记现金」的兜底口径。
     */
    private BillResult.Collected buildCollected(OrderPo order, Long orderId, long paidMinor) {
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

    private OrderPo requireOrder(Long orderId) {
        OrderPo order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        }
        return order;
    }

    private List<OrderItemPo> queryItems(Long orderId) {
        QueryWrapper<OrderItemPo> qw = new QueryWrapper<>();
        qw.eq("order_id", orderId);
        // 账单只计入已生效的加项；C 端提交的待确认(PENDING_APPROVAL)/已拒绝(REJECTED)加项不计入。
        qw.eq("status", "ACTIVE");
        qw.orderByAsc("id");
        return orderItemMapper.selectList(qw);
    }

    private List<KtvSessionPo> querySessions(Long orderId) {
        QueryWrapper<KtvSessionPo> qw = new QueryWrapper<>();
        qw.eq("order_id", orderId);
        return ktvSessionMapper.selectList(qw);
    }

    private List<KtvServerSessionPo> queryServerSessions(Long orderId) {
        QueryWrapper<KtvServerSessionPo> qw = new QueryWrapper<>();
        qw.eq("order_id", orderId);
        return serverSessionMapper.selectList(qw);
    }
}
