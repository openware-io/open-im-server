package io.openware.platform.order.hotel.channel;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * OTA 渠道预订映射（channel side idempotency ledger）。
 *
 * <p>一条记录 = 一个 (channel_code, external_ref) 外部单号 → 内部 htl_booking 的映射，
 * 不复制酒店预订状态机本身：{@code status} 只是投影，权威状态始终以 {@code htl_booking.status}
 * 为准（查询/取消响应读实时状态，本表状态在写路径同步）。
 */
@Getter
@Setter
@TableName("htl_channel_booking")
public class HotelChannelBookingPo {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private String channelCode;
    private String externalRef;
    private Long bookingId;
    private String bookingNo;
    private String requestHash;
    private String status;
    private String lastAction;
    private String cancelReason;
    /** 审计轨迹：JSON 数组，按动作追加，不覆盖历史。 */
    private String auditJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
