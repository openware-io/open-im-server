package io.openware.platform.order.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 包厢会话持久化对象：与 {@code ord_ktv_session} 逐列对应。
 *
 * <p>本次迁移删除了原有两个 {@code @TableField(exist = false)} 的展示字段
 * （{@code elapsedSeconds} 已计费时长 / {@code estimatedRoomFee} 实时房费估算）：
 * 它们是**读投影**（由计价方案在响应期算出），既不是会话自身的事实、也不落库，
 * 已经移到响应视图 {@code KtvSessionView} 上。留下来只会给写入路径留一个
 * 「对非列字段生成列引用 → 运行时 {@code can not find lambda cache for this property}」的坑，
 * 而 {@code test-compile} 看不出任何异常。
 */
@Getter
@Setter
@TableName("ord_ktv_session")
public class KtvSessionPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    /** 组织快照（V24 新增列）：创建会话时从订单 organization_id 复制，报表/权限按组织归集不再跨域回查。 */
    private Long organizationId;
    private Long storeId;
    private String businessType;
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
}
