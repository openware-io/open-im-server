package io.openware.platform.order.domain.ktv.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 服务人员点单（领域模型）：{@code ord_ktv_server_session} 的聚合根，
 * 状态机 ORDERED → SERVING → ENDED，异常终结 CANCELLED。
 *
 * <p><b>只有真实列，且不含审计字段</b>（standards/10）：{@code created_by / created_at / updated_by /
 * updated_at} 由适配器写入时填充、读取时丢弃。
 *
 * <p><b>没有非列成员</b>：原持久化对象 {@code KtvServerSessionPo} 的 26 个字段全部是列，
 * 因此本模型与表逐列对应，写入路径不可能对不存在的列生成引用。
 *
 * <p><b>{@code version} 保留</b>：与 {@link KtvSession} 同理，{@code ord_ktv_server_session} 没有
 * MyBatis-Plus 的 {@code @Version} 保护，并发唯一屏障是显式的
 * {@code UPDATE ... WHERE id = ? AND version = ?}（见
 * {@code KtvServerSessionRepository#updateWithVersion}）。
 *
 * <p>不可变记录：字段变更一律返回新实例（{@code with*}），没有 setter。
 *
 * @param id                 点单标识；{@code null} 表示尚未落库
 * @param tenantId           租户
 * @param storeId            门店（点单时从订单快照）
 * @param businessType       业态（点单时从订单快照）
 * @param orderId            所属订单
 * @param ktvSessionId       所属包厢会话；历史数据可能为空（免费名额判定退化为按订单）
 * @param serverResourceId   服务人员资源（{@code res_resource.id}）
 * @param catalogItemId      服务对应目录项（生成 SERVICE 明细时回指）
 * @param orderedAt          点单时刻（结束服务时若缺开始时刻，用它作为计时起点）
 * @param startedAt          开始服务时刻
 * @param endedAt            结束服务时刻
 * @param billingUnit        计费单位快照
 * @param incrementMinutes   递增粒度（分钟）
 * @param roundingDirection  舍入方向快照（默认让利消费者）
 * @param pricePerInc        每递增粒度单价（最小货币单位）
 * @param durationSeconds    固化时长（秒）
 * @param durationMinutes    固化时长（按递增粒度与舍入方向换算出的分钟数）
 * @param totalAmount        固化金额（最小货币单位；占用免费名额时为 0）
 * @param currencyCode       币种快照（落库时的租户币种）
 * @param priceSnapshotJson  计价规则快照（JSON 文本，落库为列；序列化只在适配器里发生）
 * @param status             ORDERED / SERVING / ENDED / CANCELLED
 * @param version            乐观锁版本号列；CAS 写入时作为期望版本
 */
public record KtvServerSession(
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
        Integer version) {

    /**
     * 新建点单（{@code status=ORDERED}、{@code version=0}、时长与金额归零）。
     *
     * <p>字段集合与原 {@code KtvServerSessionApplicationService#order} 构造持久化对象时逐项对应：
     * 计费单位/递增粒度/舍入方向/单价/规则快照全部来自当时解析出的计价方案，
     * 币种快照继承所属订单。
     */
    public static KtvServerSession ordered(Long tenantId, Long storeId, String businessType, Long orderId,
            Long ktvSessionId, Long serverResourceId, Long catalogItemId, LocalDateTime orderedAt,
            KtvPricingPlan plan, String currencyCode) {
        return new KtvServerSession(null, tenantId, storeId, businessType, orderId, ktvSessionId,
                serverResourceId, catalogItemId, orderedAt, null, null, plan.billingUnit().name(),
                plan.incrementMinutes(), plan.roundingDirection().name(), plan.serverPricePerInc(),
                0, 0, BigDecimal.ZERO, currencyCode, plan.toSnapshotJson(),
                KtvServerSessionStatus.ORDERED.name(), 0);
    }

    /** 落库后回填主键（返回新实例）。 */
    public KtvServerSession withId(Long newId) {
        return new KtvServerSession(newId, tenantId, storeId, businessType, orderId, ktvSessionId,
                serverResourceId, catalogItemId, orderedAt, startedAt, endedAt, billingUnit, incrementMinutes,
                roundingDirection, pricePerInc, durationSeconds, durationMinutes, totalAmount, currencyCode,
                priceSnapshotJson, status, version);
    }

    /** 状态流转（返回新实例）。 */
    public KtvServerSession withStatus(String newStatus) {
        return new KtvServerSession(id, tenantId, storeId, businessType, orderId, ktvSessionId,
                serverResourceId, catalogItemId, orderedAt, startedAt, endedAt, billingUnit, incrementMinutes,
                roundingDirection, pricePerInc, durationSeconds, durationMinutes, totalAmount, currencyCode,
                priceSnapshotJson, newStatus, version);
    }

    /** 开始服务时刻写入（返回新实例）。 */
    public KtvServerSession withStartedAt(LocalDateTime newStartedAt) {
        return new KtvServerSession(id, tenantId, storeId, businessType, orderId, ktvSessionId,
                serverResourceId, catalogItemId, orderedAt, newStartedAt, endedAt, billingUnit, incrementMinutes,
                roundingDirection, pricePerInc, durationSeconds, durationMinutes, totalAmount, currencyCode,
                priceSnapshotJson, status, version);
    }

    /** 结束服务时刻写入（返回新实例）。 */
    public KtvServerSession withEndedAt(LocalDateTime newEndedAt) {
        return new KtvServerSession(id, tenantId, storeId, businessType, orderId, ktvSessionId,
                serverResourceId, catalogItemId, orderedAt, startedAt, newEndedAt, billingUnit, incrementMinutes,
                roundingDirection, pricePerInc, durationSeconds, durationMinutes, totalAmount, currencyCode,
                priceSnapshotJson, status, version);
    }

    /** 固化时长（秒）写入（返回新实例）。 */
    public KtvServerSession withDurationSeconds(Integer newDurationSeconds) {
        return new KtvServerSession(id, tenantId, storeId, businessType, orderId, ktvSessionId,
                serverResourceId, catalogItemId, orderedAt, startedAt, endedAt, billingUnit, incrementMinutes,
                roundingDirection, pricePerInc, newDurationSeconds, durationMinutes, totalAmount, currencyCode,
                priceSnapshotJson, status, version);
    }

    /** 固化时长（分钟）写入（返回新实例）。 */
    public KtvServerSession withDurationMinutes(Integer newDurationMinutes) {
        return new KtvServerSession(id, tenantId, storeId, businessType, orderId, ktvSessionId,
                serverResourceId, catalogItemId, orderedAt, startedAt, endedAt, billingUnit, incrementMinutes,
                roundingDirection, pricePerInc, durationSeconds, newDurationMinutes, totalAmount, currencyCode,
                priceSnapshotJson, status, version);
    }

    /** 固化金额写入（返回新实例）。 */
    public KtvServerSession withTotalAmount(BigDecimal newTotalAmount) {
        return new KtvServerSession(id, tenantId, storeId, businessType, orderId, ktvSessionId,
                serverResourceId, catalogItemId, orderedAt, startedAt, endedAt, billingUnit, incrementMinutes,
                roundingDirection, pricePerInc, durationSeconds, durationMinutes, newTotalAmount, currencyCode,
                priceSnapshotJson, status, version);
    }

    /** 币种快照写入（结束服务时兜底归一，返回新实例）。 */
    public KtvServerSession withCurrencyCode(String newCurrencyCode) {
        return new KtvServerSession(id, tenantId, storeId, businessType, orderId, ktvSessionId,
                serverResourceId, catalogItemId, orderedAt, startedAt, endedAt, billingUnit, incrementMinutes,
                roundingDirection, pricePerInc, durationSeconds, durationMinutes, totalAmount, newCurrencyCode,
                priceSnapshotJson, status, version);
    }

    /** 版本号写入（返回新实例）。 */
    public KtvServerSession withVersion(Integer newVersion) {
        return new KtvServerSession(id, tenantId, storeId, businessType, orderId, ktvSessionId,
                serverResourceId, catalogItemId, orderedAt, startedAt, endedAt, billingUnit, incrementMinutes,
                roundingDirection, pricePerInc, durationSeconds, durationMinutes, totalAmount, currencyCode,
                priceSnapshotJson, status, newVersion);
    }
}
