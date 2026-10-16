package io.openware.common.payment.application;

import io.openware.common.payment.domain.closing.model.DailyClosing;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日结响应 DTO（由领域模型构造；含币种快照 {@code currencyCode}：日结按币种出具，跨币种不得合并）。
 *
 * <p>{@code summary} 是 {@code pay_daily_closing.summary_json} 的反序列化结果（按币种分组的收款/现金/
 * 退款/交班长短款汇总，金额为最小货币单位整数）；历史行（本改动之前从不写入汇总）与损坏结构降级为
 * {@code null}，其余既有字段一律不变。
 *
 * <p>不暴露记账字段（createdBy、updatedBy）：它们是审计事实，由审计留痕承载
 * （standards/10 的 D2 规则）。{@code submittedBy}/{@code reviewedBy} 保留——提交人与复核人是业务角色。
 */
public record DailyClosingDto(Long id, Long tenantId, Long storeId, LocalDate businessDate, Long submittedBy,
                              Long reviewedBy, String currencyCode, String status, LocalDateTime createdAt,
                              LocalDateTime updatedAt, DailyClosingSummary summary) {

    /** 由领域模型构造；入参为空时返回空。 */
    public static DailyClosingDto from(DailyClosing closing) {
        if (closing == null) {
            return null;
        }
        return new DailyClosingDto(closing.id(), closing.tenantId(), closing.storeId(),
                closing.businessDate(), closing.submittedBy(), closing.reviewedBy(),
                closing.currencyCode(), closing.status(), closing.createdAt(), closing.updatedAt(),
                DailyClosingSummary.parse(closing.summaryJson()));
    }
}
