package io.openware.common.payment.application;

import io.openware.common.payment.infra.persistence.po.DailyClosingPo;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日结响应 DTO（含币种快照 {@code currencyCode}：日结按币种出具，跨币种不得合并）。
 *
 * <p>{@code summary} 是 {@code pay_daily_closing.summary_json} 的反序列化结果（按币种分组的收款/现金/
 * 退款/交班长短款汇总，金额为最小货币单位整数）；历史行（本改动之前从不写入汇总）与损坏结构降级为
 * {@code null}，其余既有字段一律不变。
 */
public record DailyClosingDto(Long id, Long tenantId, Long storeId, LocalDate businessDate, Long submittedBy,
                              Long reviewedBy, String currencyCode, String status, Long createdBy, LocalDateTime createdAt,
                              Long updatedBy, LocalDateTime updatedAt, DailyClosingSummary summary) {
    public static DailyClosingDto from(DailyClosingPo po) {
        return new DailyClosingDto(po.getId(), po.getTenantId(), po.getStoreId(), po.getBusinessDate(), po.getSubmittedBy(),
                po.getReviewedBy(), po.getCurrencyCode(), po.getStatus(), po.getCreatedBy(), po.getCreatedAt(),
                po.getUpdatedBy(), po.getUpdatedAt(), DailyClosingSummary.parse(po.getSummaryJson()));
    }
}
