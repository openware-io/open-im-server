package com.gvchat.platform.order.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("ord_order")
public class OrderPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 非持久化字段：快速开台时同步创建的 KTV 会话 ID（ord_ktv_session.id），仅返回给调用方。 */
    @TableField(exist = false)
    private Long sessionId;
    /** 非持久化：包厢资源 ID（来自 KTV 会话），订单列表展示包厢用。 */
    @TableField(exist = false)
    private Long roomResourceId;
    /** 非持久化：包厢名称快照（如「小包 K01」）。 */
    @TableField(exist = false)
    private String roomName;
    /** 非持久化：包厢编码快照（如 K01）。 */
    @TableField(exist = false)
    private String roomCode;
    /** 非持久化：KTV 会话状态（RESERVED/OPEN/PAUSED/CLOSED/CANCELLED）。 */
    @TableField(exist = false)
    private String sessionStatus;
    /** 非持久化：到店人数（会话 party_size，订单列表与房态看板展示「人数」）。 */
    @TableField(exist = false)
    private Integer partySize;
    /** 非持久化：服务人员资源 ID（会话 server_id 快照）。 */
    @TableField(exist = false)
    private Long serverId;
    /** 非持久化：服务人员名称（会话 server_name 快照；资源服务不可达时为 null）。 */
    @TableField(exist = false)
    private String serverName;
    /** 非持久化：开台中的已计费时长（秒）。 */
    @TableField(exist = false)
    private Long roomElapsedSeconds;
    /** 非持久化：开台中的包厢计时费实时估算（最小货币单位）。 */
    @TableField(exist = false)
    private Long roomEstimatedFee;
    /**
     * 非持久化：**实时应付合计**（最小货币单位，与 {@link #totalAmount} 同单位同口径：金额列里存的就是分/cent 整数）。
     *
     * <p>为什么要单独给一个字段：{@code totalAmount} 是库内快照——开台中它包含的房费是
     * ROOM_FEE 明细里「上次刷新」（{@code KtvRoomFeeRefreshJob}）写入的值，不是此刻的房费；
     * 而 {@code roomEstimatedFee} 是此刻的实时估算。客户端把两者**相加**就会把包厢费算两遍
     * （收银台房卡金额因此比账单多出一笔房费）。本字段由服务端算好：
     * 开台中 = 库内合计 − 房费明细快照 + 本会话实时估算（与账单 {@code BillApplicationService#buildBill}
     * 的差额法同口径）；未开台 = 库内合计（已是终值）。
     *
     * <p>客户端**直接展示本字段**，不要再自行加减 {@code roomEstimatedFee}。
     * {@code totalAmount} 保持库内原值不变（它不是展示字段，不得改写）。
     */
    @TableField(exist = false)
    private BigDecimal liveTotalAmount;
    /**
     * 非持久化：**当前这次开台**（会话语义）的开台时刻与结台时刻。
     *
     * <p>订单维度的「消费时间」必须看会话，不能看订单：订单的 {@code created_at} 是下单时刻（预约单可能早于
     * 开台），{@code completed_at} 是订单收口时刻；两者之间可能夹着多次开台，直接相减会把多次消费算成一段。
     * 同一订单有多个会话时这里给的是「当前会话」（OPEN/PAUSED 优先，其次最新一条）。
     */
    @TableField(exist = false)
    private LocalDateTime sessionOpenedAt;
    @TableField(exist = false)
    private LocalDateTime sessionClosedAt;
    private Long tenantId;
    private Long organizationId;
    private Long storeId;
    private String orderNo;
    /**
     * 幂等键（请求头 Idempotency-Key，租户内唯一，V28 新增）：
     * 快速开台的重试/重复点击命中同一键时回放已有订单，不再建第二张单；NULL = 调用方未提供。
     */
    private String idempotencyKey;
    private String businessType;
    private Long customerId;
    private String status;
    private String currencyCode;
    private BigDecimal subtotalAmount;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    private BigDecimal refundableAmount;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;
    /** 挂单原因（KTV_BUSINESS_01 §11.1：挂单不结台、不改商业状态）。 */
    private String holdReason;
    /** 挂单时间。 */
    private LocalDateTime holdAt;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
