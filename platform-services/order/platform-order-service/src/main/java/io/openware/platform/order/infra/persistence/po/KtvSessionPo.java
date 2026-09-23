package io.openware.platform.order.infra.persistence.po;

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
@TableName("ord_ktv_session")
public class KtvSessionPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long orderId;
    private Long roomResourceId;
    /** 资源占用ID（开台占用，结台释放；取消/转台同步处理）。 */
    private Long occupationId;
    /** 包厢名称快照（资源域读一次写下来，订单列表与账单展示包厢不依赖跨域调用）。 */
    private String roomNameSnapshot;
    private String roomCodeSnapshot;
    /** 到店人数（开台登记，NULL=未登记）；订单列表与房态看板展示「人数」。 */
    private Integer partySize;
    /** 服务人员资源ID快照（点服务人员时写入本会话）。 */
    private Long serverId;
    /** 服务人员名称快照；资源服务不可达时为 NULL（前端退回「服务人员#<id>」）。 */
    private String serverName;
    private LocalDateTime reservedStartAt;
    private LocalDateTime reservedEndAt;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private String billingUnit;
    private LocalDateTime billingStartAt;
    private Integer freeWaitMinutes;
    private Integer pausedSeconds;
    private LocalDateTime pauseStartedAt;
    private BigDecimal overtimeRate;
    private String billingRuleSnapshotJson;
    private String status;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;

    /** 非持久化：已计费时长（秒），开台中实时计算，供订单/会话展示「包厢计时中」。 */
    @TableField(exist = false)
    private Long elapsedSeconds;
    /** 非持久化：当前包厢计时费（最小货币单位），开台中按计价方案实时估算。 */
    @TableField(exist = false)
    private Long estimatedRoomFee;
}
