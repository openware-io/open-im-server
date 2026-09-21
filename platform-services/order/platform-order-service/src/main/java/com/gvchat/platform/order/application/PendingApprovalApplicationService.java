package com.gvchat.platform.order.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.application.dto.PendingApprovalView;
import com.gvchat.platform.order.infra.cache.PendingApprovalCache;
import com.gvchat.platform.order.infra.persistence.mapper.OrderItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.po.KtvSessionPo;
import com.gvchat.platform.order.infra.persistence.po.OrderItemPo;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「客户待确认加项」聚合查询（P0 提醒链路的数据源）。
 *
 * <p><b>业务背景</b>：C 端客户自助加项落库即 {@code PENDING_APPROVAL}（不计入应收），需要门店确认/拒绝；
 * 此前只有「点单/加项」弹窗里能看到，运营不知道客户加了什么。本服务把「本门店待确认加项」聚合成一份视图，
 * 供后台角标/收银台卡片/订单管理列表/App 横幅/B 端卡片共用。
 *
 * <p><b>一致性口径</b>：
 * <ul>
 *   <li>只统计**当前签名上下文门店**（{@code ord_order.store_id}）的未确认项：明细表没有 store_id，
 *       因此按 orderId 批量回查订单再按门店过滤（一次 IN 查询，不逐单回查）；</li>
 *   <li>计数与金额由**全部命中行**汇总：分页/裁剪只影响明细条数，不影响 {@code pendingCount/pendingAmount}；</li>
 *   <li>金额是最小货币单位 + 明细币种快照；混币种时 {@code currencyCode=null}、{@code mixedCurrency=true}，
 *       调用方不得相加（规范 16 §3）；</li>
 *   <li>{@code revision} = 命中行最大 id：客户端轮询时 revision 未变即可跳过重渲染（避免抖动）。</li>
 * </ul>
 *
 * <p><b>实时性</b>：读路径走 {@link PendingApprovalCache}（TTL 默认 3s）；写路径（客户提交/确认/拒绝）
 * 提交后立即 {@link PendingApprovalCache#invalidate}，本实例的角标在写返回后立刻更新，其它实例最坏等一个 TTL。
 *
 * <p><b>未来消息中心</b>：本类是「提醒」的唯一读入口；接入消息中心后，客户提交加项时发布事件、
 * 客户端改订阅推送，本类可保留为兜底快照接口（或整体替换），调用方无需改口径。
 */
@Service
public class PendingApprovalApplicationService {

    /** 明细状态：待服务人员确认（与 OrderItemController 的写入值一致）。 */
    private static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";

    /** 单次聚合最多返回的明细条数（防极端数据把响应撑爆；计数与金额仍按全部命中行统计）。 */
    private static final int MAX_ITEMS = 200;

    private final OrderItemMapper orderItemMapper;
    private final OrderMapper orderMapper;
    private final KtvSessionApplicationService ktvSessionService;
    private final PendingApprovalCache cache;

    public PendingApprovalApplicationService(OrderItemMapper orderItemMapper, OrderMapper orderMapper,
                                            KtvSessionApplicationService ktvSessionService,
                                            PendingApprovalCache cache) {
        this.orderItemMapper = orderItemMapper;
        this.orderMapper = orderMapper;
        this.ktvSessionService = ktvSessionService;
        this.cache = cache;
    }

    /** 当前上下文门店的待确认加项（命中缓存直接返回）。 */
    public PendingApprovalView pendingForCurrentStore() {
        TenantContext context = TenantContextHolder.get();
        Long tenantId = context == null ? null : context.tenantId();
        Long storeId = context == null ? null : context.storeId();
        return pending(tenantId, storeId);
    }

    /** 指定租户 + 门店的待确认加项（缓存 + 回源）。 */
    public PendingApprovalView pending(Long tenantId, Long storeId) {
        var cached = cache.get(tenantId, storeId);
        if (cached.isPresent()) {
            return cached.get();
        }
        PendingApprovalView view = load(tenantId, storeId);
        cache.put(tenantId, storeId, view);
        return view;
    }

    /** 写路径后失效（客户提交加项 / 运营确认 / 拒绝）。 */
    public void invalidate(Long tenantId, Long storeId) {
        cache.invalidate(tenantId, storeId);
    }

    /** 按当前上下文失效（写路径调用；拿不到门店时不失效，靠 TTL 收敛）。 */
    public void invalidateCurrentStore() {
        TenantContext context = TenantContextHolder.get();
        if (context != null) {
            cache.invalidate(context.tenantId(), context.storeId());
        }
    }

    /**
     * 回源：待确认明细（按提交时间正序）→ 批量回查订单（按门店过滤）→ 回填包厢快照 → 聚合。
     * 三次查询，与命中订单数无关（不逐单回查）。
     */
    private PendingApprovalView load(Long tenantId, Long storeId) {
        long now = System.currentTimeMillis();
        if (tenantId == null || storeId == null) {
            return empty(now);
        }
        List<OrderItemPo> pending = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItemPo>()
                .eq(OrderItemPo::getTenantId, tenantId)
                .eq(OrderItemPo::getStatus, STATUS_PENDING_APPROVAL)
                .orderByAsc(OrderItemPo::getCreatedAt)
                .orderByAsc(OrderItemPo::getId)
                .last("LIMIT " + MAX_ITEMS));
        if (pending == null || pending.isEmpty()) {
            return empty(now);
        }
        Set<Long> orderIds = new LinkedHashSet<>();
        pending.forEach(item -> {
            if (item.getOrderId() != null) {
                orderIds.add(item.getOrderId());
            }
        });
        if (orderIds.isEmpty()) {
            return empty(now);
        }
        Map<Long, OrderPo> orders = new LinkedHashMap<>();
        orderMapper.selectBatchIds(orderIds).stream()
                .filter(order -> storeId.equals(order.getStoreId()))
                .forEach(order -> orders.put(order.getId(), order));
        Map<Long, KtvSessionPo> sessions = ktvSessionService.listByOrderIds(new ArrayList<>(orders.keySet()));

        Map<Long, List<OrderItemPo>> grouped = new LinkedHashMap<>();
        long pendingCount = 0;
        long pendingAmount = 0;
        long revision = 0;
        Set<String> currencies = new LinkedHashSet<>();
        for (OrderItemPo item : pending) {
            OrderPo order = item.getOrderId() == null ? null : orders.get(item.getOrderId());
            if (order == null) {
                // 不是当前门店（或订单已不可见）：不计入本门店口径。
                continue;
            }
            grouped.computeIfAbsent(order.getId(), key -> new ArrayList<>()).add(item);
            pendingCount += 1;
            pendingAmount += minor(item.getTotalAmount());
            revision = Math.max(revision, item.getId() == null ? 0L : item.getId());
            if (item.getCurrencyCode() != null && !item.getCurrencyCode().isBlank()) {
                currencies.add(item.getCurrencyCode());
            }
        }
        List<PendingApprovalView.PendingOrder> groupViews = new ArrayList<>();
        grouped.forEach((orderId, items) -> {
            OrderPo order = orders.get(orderId);
            KtvSessionPo session = sessions == null ? null : sessions.get(orderId);
            long groupAmount = 0;
            List<PendingApprovalView.PendingItem> itemViews = new ArrayList<>(items.size());
            for (OrderItemPo item : items) {
                long amount = minor(item.getTotalAmount());
                groupAmount += amount;
                itemViews.add(new PendingApprovalView.PendingItem(
                        item.getId(),
                        item.getNameSnapshot(),
                        item.getQuantity(),
                        minor(item.getUnitPrice()),
                        amount,
                        item.getCurrencyCode(),
                        item.getCreatedAt() == null ? null : item.getCreatedAt().toString()));
            }
            groupViews.add(new PendingApprovalView.PendingOrder(
                    orderId,
                    order.getOrderNo(),
                    order.getStoreId(),
                    roomNameOf(session),
                    session == null ? null : text(session.getRoomCodeSnapshot()),
                    session == null ? null : session.getStatus(),
                    session == null ? null : session.getElapsedSeconds(),
                    itemViews.size(),
                    groupAmount,
                    itemViews));
        });
        // 有未确认加项的订单排在前面；同组内按最早提交时间（分组本身按明细时间正序建立，保持稳定）。
        groupViews.sort(Comparator.comparing(PendingApprovalView.PendingOrder::orderId));
        String currencyCode = currencies.size() == 1 ? currencies.iterator().next() : null;
        return new PendingApprovalView(pendingCount, pendingAmount, currencyCode, currencies.size() > 1,
                revision, now, groupViews);
    }

    private static PendingApprovalView empty(long now) {
        return new PendingApprovalView(0L, 0L, null, false, 0L, now, List.of());
    }

    /**
     * 包厢展示名（三端「待确认加项」都靠它回答「是哪间包厢的需求」）：
     * 会话名称快照 → 会话编码快照 → 按 {@code roomResourceId} **回源资源服务** → null。
     *
     * <p>为什么要有后两级：包厢名/编码是开台那一刻从资源服务取的快照，开台时资源服务不可达
     * （或历史数据）就只剩资源 ID，前端只能显示「—」，门店根本不知道是哪间包厢点的。
     * 回源失败仍返回 null（读路径降级，不打断提醒链路），由前端统一显示「未关联包厢」。
     */
    private String roomNameOf(KtvSessionPo session) {
        if (session == null) {
            return null;
        }
        String name = text(session.getRoomNameSnapshot());
        if (name != null) {
            return name;
        }
        String code = text(session.getRoomCodeSnapshot());
        if (code != null) {
            return code;
        }
        return ktvSessionService == null ? null : text(ktvSessionService.roomNameFromResource(session.getRoomResourceId()));
    }

    /** 去空白；空白与 null 一视同仁（快照列可能存了空串）。 */
    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 最小货币单位整数：明细金额是 BigDecimal，聚合只做「分」口径相加。 */
    private static long minor(BigDecimal value) {
        return value == null ? 0L : value.longValue();
    }
}
