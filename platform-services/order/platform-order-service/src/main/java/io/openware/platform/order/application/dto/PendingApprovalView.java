package io.openware.platform.order.application.dto;

import java.util.List;

/**
 * 「客户待确认加项」聚合视图（后台角标 / 收银台卡片标记 / 订单管理列表 / App 横幅 / B 端卡片角标共用一份）。
 *
 * <p><b>一个数据源</b>：所有提示都读这个结构，避免各端各写一套查询与计数口径（多端数字不一致是这类提醒
 * 功能最常见的坑）。分组按订单，组内按提交时间正序（先到先处理）。
 *
 * <p><b>一致性</b>：{@code pendingCount}/{@code pendingAmount} 由服务端按**全部命中行**汇总，
 * 不随分页/裁剪漂移；金额是最小货币单位、且只在同一币种时给出 {@code currencyCode}，混币种时
 * {@code currencyCode=null} 且 {@code mixedCurrency=true}（禁止跨币种相加，规范 16 §3）。
 *
 * <p><b>实时性字段</b>：{@code revision} 取「命中行的最大 id」——客户端轮询时只要 revision 不变就可跳过重渲染；
 * {@code serverTimeMillis} 供客户端校准「已等待时长」。
 */
public record PendingApprovalView(
        long pendingCount,
        long pendingAmount,
        String currencyCode,
        boolean mixedCurrency,
        long revision,
        long serverTimeMillis,
        List<PendingOrder> orders) {

    /**
     * 单个订单下的待确认加项。
     *
     * <p><b>包厢字段（门店必须一眼看出是哪间包厢的需求）</b>：
     * {@code roomName} 是**可直接展示**的包厢名，取值顺序为「会话名称快照 → 会话编码快照 →
     * 按 roomResourceId 回源资源服务」；{@code roomCode} 是会话的包厢编码快照。
     * 订单没有 KTV 会话（非包厢单）或资源服务不可达时为 {@code null}，三端统一显示「未关联包厢」。
     */
    public record PendingOrder(
            Long orderId,
            String orderNo,
            Long storeId,
            String roomName,
            String roomCode,
            String sessionStatus,
            Long orderElapsedSeconds,
            long pendingCount,
            long pendingAmount,
            List<PendingItem> items) {}

    /** 单条待确认加项。 */
    public record PendingItem(
            Long id,
            String name,
            java.math.BigDecimal quantity,
            long unitPrice,
            long amount,
            String currencyCode,
            String createdAt) {}
}
