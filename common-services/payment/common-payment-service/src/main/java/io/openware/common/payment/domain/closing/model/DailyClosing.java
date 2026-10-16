package io.openware.common.payment.domain.closing.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日结记录（领域模型）。
 *
 * <p>审计字段（createdBy、updatedBy）不在此承载：由持久化适配器填充。
 *
 * <p>{@code businessDate} 是**营业日**（受门店营业日切点影响），不是自然日：
 * 凌晨 2 点的交班属于"前一天"的账，按自然日归集会把同一营业日的流水切成两天。
 *
 * <p>{@code summaryJson} 保持原始 JSON 文本而不在此解析：汇总结构属于展示层契约，
 * 领域只负责保证它是原样存取的事实。
 *
 * @param id           日结标识
 * @param tenantId     租户
 * @param storeId      门店
 * @param businessDate 营业日
 * @param submittedBy  提交人
 * @param reviewedBy   复核人
 * @param currencyCode 币种快照
 * @param summaryJson  汇总明细（JSON 原文）
 * @param status       状态
 * @param createdAt    创建时刻
 * @param updatedAt    更新时刻
 */
public record DailyClosing(
    Long id,
    Long tenantId,
    Long storeId,
    LocalDate businessDate,
    Long submittedBy,
    Long reviewedBy,
    String currencyCode,
    String summaryJson,
    String status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {
}
