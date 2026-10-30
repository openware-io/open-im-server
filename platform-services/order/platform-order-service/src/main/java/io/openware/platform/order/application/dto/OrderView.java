package io.openware.platform.order.application.dto;

import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.RefundStatus;
import io.openware.platform.order.domain.order.model.TradeStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单的对外视图（{@code /business/orders/**}、{@code /me/orders} 的响应体）。
 *
 * <p><b>KTV 订单状态语义（目标契约 §4/§7）</b>：KTV 订单 DTO 必须**同时**返回
 * {@code tradeStatus}（交易状态，{@link TradeStatus}）、{@code sessionStatus}（会话状态，
 * {@code RESERVED/OPEN/PAUSED/CLOSED/CANCELLED}）与 {@code settlementStatus}（结算派生状态）；
 * 前端不再靠单一状态推断业务阶段，旧兼容列 {@code status} 已在收口迁移中删除。
 *
 * <p><b>字段集照搬原持久化对象 {@code OrderPo}（JSON 字段名一个不改）</b>：这些端点响应早已是对外契约
 * （见 {@code docs/contracts/openapi/platform-order-service.json} 的 {@code OrderPo} schema），
 * 删字段是破坏已发布契约，因此全部保留；新增字段是向后兼容的追加。
 *
 * <p><b>13 个非列字段：会话投影</b>。{@code sessionId} / {@code roomResourceId} / {@code roomName} /
 * {@code roomCode} / {@code sessionStatus} / {@code partySize} / {@code serverId} / {@code serverName} /
 * {@code roomElapsedSeconds} / {@code roomEstimatedFee} / {@code liveTotalAmount} /
 * {@code sessionOpenedAt} / {@code sessionClosedAt} 在原持久化对象上标了
 * {@code @TableField(exist = false)}——它们是从 {@code ord_ktv_session} 联表读出的**读投影**
 * （谁在哪个包厢、开台多久、此刻房费多少），不是订单自身的业务事实，因此：
 * <ul>
 *   <li>**不在领域模型 {@link Order} 上**：领域模型一旦承载非列成员，写入路径就可能为不存在的列生成引用，
 *       MyBatis-Plus 会在**运行时**抛 {@code can not find lambda cache for this property [...]}，
 *       而 {@code test-compile} 完全干净；</li>
 *   <li>由读路径在装配响应时逐个回填（见 {@code OrderController#fillSessionProjection}），
 *       本视图提供 {@code with*} 系列方法承载这份投影；</li>
 *   <li>不可能进入任何列引用：本类型从不被转换成持久化对象。</li>
 * </ul>
 *
 * <p><b>审计四列保留、但不假装有值</b>：{@code createdBy}/{@code createdAt}/{@code updatedBy}/
 * {@code updatedAt} 是记账信息、不进领域模型（standards/10 D2）。它们仍在响应契约里，因此字段名保留；
 * 本视图的装配口径是「只有真的读到记账快照才填」——订单详情/创建/挂单/取消这些路径没有回读记账值，
 * 这四列取 {@code null}。
 *
 * @param id                   订单标识
 * @param sessionId            非列：本单当前会话（{@code ord_ktv_session.id}，快速开台同步创建/幂等回放补齐）
 * @param roomResourceId       非列：包厢资源
 * @param roomName             非列：包厢名称快照
 * @param roomCode             非列：包厢编码快照
 * @param sessionStatus        非列：会话状态（RESERVED/OPEN/PAUSED/CLOSED/CANCELLED）
 * @param partySize            非列：到店人数
 * @param serverId             非列：服务人员资源
 * @param serverName           非列：服务人员名称快照
 * @param roomElapsedSeconds   非列：开台中的已计费时长（秒）
 * @param roomEstimatedFee     非列：开台中的包厢计时费实时估算（最小货币单位）
 * @param liveTotalAmount      非列：**可直接展示的实时应付合计**（最小货币单位）
 * @param sessionOpenedAt      非列：当前会话开台时刻
 * @param sessionClosedAt      非列：当前会话结台时刻
 * @param tenantId             租户
 * @param organizationId       组织
 * @param storeId              门店
 * @param orderNo              订单号
 * @param idempotencyKey       幂等键
 * @param businessType         业态
 * @param customerId           会员
 * @param tradeStatus          交易状态：DRAFT/PENDING_PAYMENT/PAID/COMPLETED/CANCELLED/VOIDED
 * @param displayStatus        用户可见状态投影；C 端酒店订单使用酒店预订状态，B 端默认等于 tradeStatus
 * @param refundStatus         退款状态：NONE/PARTIAL/FULL
 * @param settlementStatus     结算派生状态：UNSETTLED/PENDING_PAYMENT/PAID/COMPLETED/CANCELLED/REFUNDED/PARTIALLY_REFUNDED
 * @param currencyCode         币种快照
 * @param subtotalAmount       原价合计
 * @param discountAmount       优惠合计
 * @param taxAmount            税额
 * @param totalAmount          应付合计（库内快照）
 * @param paidAmount           已收金额
 * @param refundableAmount     可退金额
 * @param completedAt          完成时间
 * @param cancelledAt          作废时间
 * @param version              乐观锁版本号
 * @param createdBy            记账列：创建人（本视图不读记账快照，恒为 {@code null}）
 * @param createdAt            记账列：创建时间（同上）
 * @param updatedBy            记账列：最后更新人（同上）
 * @param updatedAt            记账列：最后更新时间（同上）
 */
public record OrderView(
    Long id,
    Long sessionId,
    Long roomResourceId,
    String roomName,
    String roomCode,
    String sessionStatus,
    Integer partySize,
    Long serverId,
    String serverName,
    Long roomElapsedSeconds,
    Long roomEstimatedFee,
    BigDecimal liveTotalAmount,
    LocalDateTime sessionOpenedAt,
    LocalDateTime sessionClosedAt,
    Long tenantId,
    Long organizationId,
    Long storeId,
    String orderNo,
    String idempotencyKey,
    String businessType,
    Long customerId,
    String tradeStatus,
    String displayStatus,
    String refundStatus,
    String settlementStatus,
    String currencyCode,
    BigDecimal subtotalAmount,
    BigDecimal discountAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    BigDecimal paidAmount,
    BigDecimal refundableAmount,
    LocalDateTime completedAt,
    LocalDateTime cancelledAt,
    Integer version,
    Long createdBy,
    LocalDateTime createdAt,
    Long updatedBy,
    LocalDateTime updatedAt) {

    /** 由领域聚合装配；13 个会话投影字段与四个审计列都为空，由读路径按需回填。 */
    public static OrderView from(Order order) {
        if (order == null) {
            return null;
        }
        TradeStatus trade = order.trade();
        RefundStatus refund = order.refund();
        return new OrderView(order.id(), null, null, null, null, null, null, null, null, null, null, null,
            null, null, order.tenantId(), order.organizationId(), order.storeId(), order.orderNo(),
            order.idempotencyKey(), order.businessType(), order.customerId(),
            trade.name(), trade.name(), refund.name(), settlementStatusOf(trade, refund), order.currencyCode(),
            order.subtotalAmount(), order.discountAmount(), order.taxAmount(), order.totalAmount(),
            order.paidAmount(), order.refundableAmount(), order.completedAt(), order.cancelledAt(),
            order.version(), null, null, null, null);
    }

    /**
     * 结算派生状态：订单主表没有 settlement_status 列（目标契约只要求 DTO 返回），
     * 因此由交易状态 + 退款状态唯一派生；退款状态优先（退款覆盖交易阶段）。
     * 取值与 {@link TradeStatus} 同名字符串，加 {@code UNSETTLED} 与两个退款态。
     */
    public static String settlementStatusOf(TradeStatus trade, RefundStatus refund) {
        if (refund == RefundStatus.FULL) {
            return "REFUNDED";
        }
        if (refund == RefundStatus.PARTIAL) {
            return "PARTIALLY_REFUNDED";
        }
        return trade == null ? "UNSETTLED" : trade.name();
    }

    /**
     * 回填 KTV 会话投影（包厢、会话状态、人数、服务人员、计时与实时合计）。
     *
     * <p>参数与顺序照搬原 {@code fillSessionProjection} 逐个 {@code set} 的字段，
     * 保证同一份投影在列表与详情两条路径上口径一致（返回新实例）。
     */
    public OrderView withSessionProjection(Long newSessionId, Long newRoomResourceId, String newRoomName,
                                           String newRoomCode, String newSessionStatus, Integer newPartySize,
                                           Long newServerId, String newServerName, Long newRoomElapsedSeconds,
                                           Long newRoomEstimatedFee, BigDecimal newLiveTotalAmount,
                                           LocalDateTime newSessionOpenedAt, LocalDateTime newSessionClosedAt) {
        return new OrderView(id, newSessionId, newRoomResourceId, newRoomName, newRoomCode, newSessionStatus,
            newPartySize, newServerId, newServerName, newRoomElapsedSeconds, newRoomEstimatedFee,
            newLiveTotalAmount, newSessionOpenedAt, newSessionClosedAt, tenantId, organizationId, storeId,
            orderNo, idempotencyKey, businessType, customerId, tradeStatus, displayStatus, refundStatus,
            settlementStatus, currencyCode, subtotalAmount, discountAmount, taxAmount, totalAmount,
            paidAmount, refundableAmount, completedAt, cancelledAt, version, createdBy,
            createdAt, updatedBy, updatedAt);
    }

    /** 只回填「当前会话 id」（幂等回放/预约开台：客户端靠它继续开台、点单）。 */
    public OrderView withSessionId(Long newSessionId) {
        return new OrderView(id, newSessionId, roomResourceId, roomName, roomCode, sessionStatus, partySize,
            serverId, serverName, roomElapsedSeconds, roomEstimatedFee, liveTotalAmount, sessionOpenedAt,
            sessionClosedAt, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
            customerId, tradeStatus, displayStatus, refundStatus, settlementStatus, currencyCode, subtotalAmount,
            discountAmount, taxAmount, totalAmount, paidAmount, refundableAmount, completedAt, cancelledAt,
            version, createdBy, createdAt, updatedBy, updatedAt);
    }

    /** 只回填实时应付合计（无会话的订单：库内合计或明细回退即终值）。 */
    public OrderView withLiveTotalAmount(BigDecimal newLiveTotalAmount) {
        return new OrderView(id, sessionId, roomResourceId, roomName, roomCode, sessionStatus, partySize,
            serverId, serverName, roomElapsedSeconds, roomEstimatedFee, newLiveTotalAmount, sessionOpenedAt,
            sessionClosedAt, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
            customerId, tradeStatus, displayStatus, refundStatus, settlementStatus, currencyCode, subtotalAmount,
            discountAmount, taxAmount, totalAmount, paidAmount, refundableAmount, completedAt, cancelledAt,
            version, createdBy, createdAt, updatedBy, updatedAt);
    }

    /** C 端读模型回填用户可见状态；B 端默认保持 tradeStatus。 */
    public OrderView withDisplayStatus(String newDisplayStatus) {
        return new OrderView(id, sessionId, roomResourceId, roomName, roomCode, sessionStatus, partySize,
            serverId, serverName, roomElapsedSeconds, roomEstimatedFee, liveTotalAmount, sessionOpenedAt,
            sessionClosedAt, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
            customerId, tradeStatus, newDisplayStatus, refundStatus, settlementStatus, currencyCode,
            subtotalAmount, discountAmount, taxAmount, totalAmount, paidAmount, refundableAmount,
            completedAt, cancelledAt, version, createdBy, createdAt, updatedBy, updatedAt);
    }
}
