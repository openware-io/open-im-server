package io.openware.common.payment.domain.shift.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收银交班（领域模型）。
 *
 * <p>审计字段（createdBy、updatedBy）不在此承载：由持久化适配器填充。
 *
 * <p>{@code currencyCode} 是**开台时的币种快照**：交班差额必须按当时的币种解释，
 * 租户事后改币种不该让历史交班的现金数字改口径。
 *
 * @param id               交班标识
 * @param tenantId         租户
 * @param storeId          门店
 * @param terminalId       终端
 * @param operatorId       操作人
 * @param openedAt         开班时刻
 * @param closedAt         交班时刻
 * @param openingCash      备用金
 * @param expectedCash     应有现金
 * @param actualCash       实点现金
 * @param differenceAmount 差额（实点 − 应有）
 * @param currencyCode     币种快照
 * @param status           状态
 * @param createdAt        创建时刻（业务时间：交班记录时序）
 * @param updatedAt        更新时刻
 */
public record Shift(
    Long id,
    Long tenantId,
    Long storeId,
    Long terminalId,
    Long operatorId,
    LocalDateTime openedAt,
    LocalDateTime closedAt,
    BigDecimal openingCash,
    BigDecimal expectedCash,
    BigDecimal actualCash,
    BigDecimal differenceAmount,
    String currencyCode,
    String status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {
}
