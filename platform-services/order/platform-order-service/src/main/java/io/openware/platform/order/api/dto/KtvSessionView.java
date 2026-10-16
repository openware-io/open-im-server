package io.openware.platform.order.api.dto;

import io.openware.platform.order.domain.ktv.model.KtvSession;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * KTV 包厢会话一行的对外视图。
 *
 * <p><b>字段名照搬原持久化对象</b>：{@code GET /business/orders/{id}/session}、
 * {@code POST /business/ktv/sessions/{id}/open} 等端点的响应体早已是对外契约
 * （见 {@code docs/contracts/openapi/platform-order-service.json} 的 {@code KtvSessionPo} schema），
 * 这里保持同一个 JSON 形状，客户端不需要改动。
 *
 * <p><b>{@code elapsedSeconds} / {@code estimatedRoomFee} 不是数据库列</b>：它们原先是
 * {@code KtvSessionPo} 上两个 {@code @TableField(exist = false)} 的展示字段，
 * 由计价方案在**响应期**算出来（开台中实时计时与实时房费），既不是会话自身的事实、也不落库。
 * 本次迁移把它们放到了这里——只出现在<b>读投影</b>上，而不是领域模型上：
 * 领域模型一旦承载非列成员，写入路径就可能对它生成列引用，而 MyBatis-Plus 对
 * {@code exist = false} 的字段生成列引用会在**运行时**抛
 * {@code can not find lambda cache for this property [...]}，{@code test-compile} 却完全干净。
 * 本类型从不被转换成持久化对象，所以这两个字段不可能进入任何列引用。
 *
 * <p><b>审计四列不在这里</b>（{@code createdBy / createdAt / updatedBy / updatedAt}）：
 * 它们是记账信息、不是业务事实（standards/10 D2），与 {@code OccupationView} 同一口径——
 * 会话的响应契约不再回显这四个字段。要恢复它们需要让每条读路径都带回审计快照
 * （{@code Persisted<T>} 包装），那是另一次有意的契约变更，不夹在本次迁移里。
 *
 * @param id                       会话标识
 * @param tenantId                 租户
 * @param storeId                  门店
 * @param businessType             业态
 * @param orderId                  所属订单
 * @param roomResourceId           包厢资源
 * @param occupationId             资源占用 ID
 * @param roomNameSnapshot         包厢名称快照
 * @param roomCodeSnapshot         包厢编码快照
 * @param partySize                到店人数（{@code null} = 未登记）
 * @param serverId                 服务人员资源 ID 快照
 * @param serverName               服务人员名称快照
 * @param reservedStartAt          预约到店时间
 * @param reservedEndAt            预约离店时间
 * @param openedAt                 开台时刻
 * @param closedAt                 结台时刻
 * @param billingUnit              计费单位快照
 * @param billingStartAt           计费起点（已含免费等待）
 * @param freeWaitMinutes          免费等待分钟数
 * @param pausedSeconds            累计暂停秒数
 * @param pauseStartedAt           本次暂停起点
 * @param overtimeRate             超时费率快照
 * @param billingRuleSnapshotJson  计价规则快照（JSON 文本）
 * @param status                   RESERVED / OPEN / PAUSED / CLOSED / CANCELLED
 * @param version                  乐观锁版本号
 * @param elapsedSeconds           非列：已计费时长（秒），仅开台中（OPEN/PAUSED）有值
 * @param estimatedRoomFee         非列：当前包厢计时费（最小货币单位），仅开台中且按时长计费时有值
 */
public record KtvSessionView(
    Long id,
    Long tenantId,
    Long storeId,
    String businessType,
    Long orderId,
    Long roomResourceId,
    Long occupationId,
    String roomNameSnapshot,
    String roomCodeSnapshot,
    Integer partySize,
    Long serverId,
    String serverName,
    LocalDateTime reservedStartAt,
    LocalDateTime reservedEndAt,
    LocalDateTime openedAt,
    LocalDateTime closedAt,
    String billingUnit,
    LocalDateTime billingStartAt,
    Integer freeWaitMinutes,
    Integer pausedSeconds,
    LocalDateTime pauseStartedAt,
    BigDecimal overtimeRate,
    String billingRuleSnapshotJson,
    String status,
    Integer version,
    Long elapsedSeconds,
    Long estimatedRoomFee) {

  /** 由领域模型装配，不带实时估算（未开台/已结台，或调用方不需要展示计时）。 */
  public static KtvSessionView from(KtvSession session) {
    return from(session, null, null);
  }

  /**
   * 由领域模型 + 实时估算装配（估算值由
   * {@code KtvSessionApplicationService#liveEstimate} 算出，看板/账单/结台共用同一实现）。
   *
   * @param elapsedSeconds   已计费时长（秒）；{@code null} = 不展示
   * @param estimatedRoomFee 实时房费（最小货币单位）；{@code null} = 不展示
   */
  public static KtvSessionView from(KtvSession session, Long elapsedSeconds, Long estimatedRoomFee) {
    if (session == null) {
      return null;
    }
    return new KtvSessionView(session.id(), session.tenantId(), session.storeId(), session.businessType(),
        session.orderId(), session.roomResourceId(), session.occupationId(), session.roomNameSnapshot(),
        session.roomCodeSnapshot(), session.partySize(), session.serverId(), session.serverName(),
        session.reservedStartAt(), session.reservedEndAt(), session.openedAt(), session.closedAt(),
        session.billingUnit(), session.billingStartAt(), session.freeWaitMinutes(), session.pausedSeconds(),
        session.pauseStartedAt(), session.overtimeRate(), session.billingRuleSnapshotJson(), session.status(),
        session.version(), elapsedSeconds, estimatedRoomFee);
  }
}
