package io.openware.platform.order.api.dto;

import io.openware.platform.order.domain.ktv.model.KtvServerSession;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 服务人员点单一行的对外视图。
 *
 * <p><b>字段名照搬原持久化对象</b>：{@code POST /business/orders/{id}/servers}、
 * {@code POST /business/ktv/servers/{id}/end|cancel} 的响应体早已是对外契约
 * （见 {@code docs/contracts/openapi/platform-order-service.json} 的 {@code KtvServerSessionPo} schema），
 * 这里保持同一个 JSON 形状，客户端不需要改动。
 *
 * <p>本类型全部字段都是真实列（原持久化对象的 26 个字段里没有
 * {@code @TableField(exist = false)}），因此不存在「非列字段被写成列引用」的风险。
 *
 * <p><b>审计四列不在这里</b>：记账信息不是业务事实（standards/10 D2），与 {@code OccupationView}
 * 同一口径。要恢复它们需要让每条读路径都带回审计快照，那是另一次有意的契约变更。
 *
 * @param id                 点单标识
 * @param tenantId           租户
 * @param storeId            门店
 * @param businessType       业态
 * @param orderId            所属订单
 * @param ktvSessionId       所属包厢会话
 * @param serverResourceId   服务人员资源
 * @param catalogItemId      服务对应目录项
 * @param orderedAt          点单时刻
 * @param startedAt          开始服务时刻
 * @param endedAt            结束服务时刻
 * @param billingUnit        计费单位快照
 * @param incrementMinutes   递增粒度（分钟）
 * @param roundingDirection  舍入方向快照
 * @param pricePerInc        每递增粒度单价（最小货币单位）
 * @param durationSeconds    固化时长（秒）
 * @param durationMinutes    固化时长（分钟）
 * @param totalAmount        固化金额（最小货币单位）
 * @param currencyCode       币种快照
 * @param priceSnapshotJson  计价规则快照（JSON 文本）
 * @param status             ORDERED / SERVING / ENDED / CANCELLED
 * @param version            乐观锁版本号
 */
public record KtvServerSessionView(
    Long id,
    Long tenantId,
    Long storeId,
    String businessType,
    Long orderId,
    Long ktvSessionId,
    Long serverResourceId,
    Long catalogItemId,
    LocalDateTime orderedAt,
    LocalDateTime startedAt,
    LocalDateTime endedAt,
    String billingUnit,
    Integer incrementMinutes,
    String roundingDirection,
    Long pricePerInc,
    Integer durationSeconds,
    Integer durationMinutes,
    BigDecimal totalAmount,
    String currencyCode,
    String priceSnapshotJson,
    String status,
    Integer version,
    Long createdBy,
    LocalDateTime createdAt,
    Long updatedBy,
    LocalDateTime updatedAt) {

  /** 由领域模型装配。 */
  public static KtvServerSessionView from(KtvServerSession session) {
    if (session == null) {
      return null;
    }
    return new KtvServerSessionView(session.id(), session.tenantId(), session.storeId(), session.businessType(),
        session.orderId(), session.ktvSessionId(), session.serverResourceId(), session.catalogItemId(),
        session.orderedAt(), session.startedAt(), session.endedAt(), session.billingUnit(),
        session.incrementMinutes(), session.roundingDirection(), session.pricePerInc(),
        session.durationSeconds(), session.durationMinutes(), session.totalAmount(), session.currencyCode(),
        session.priceSnapshotJson(), session.status(), session.version(), null, null, null, null);
  }
}
