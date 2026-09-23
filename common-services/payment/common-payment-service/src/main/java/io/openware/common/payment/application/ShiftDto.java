package io.openware.common.payment.application;

import io.openware.common.payment.infra.persistence.po.ShiftPo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交班响应 DTO（与 PO 字段一致，隔离持久层类型；含币种快照 {@code currencyCode}）。
 *
 * <p><b>金额单位（冻结）</b>：{@code openingCash}/{@code expectedCash}/{@code actualCash}/{@code differenceAmount}
 * 一律为<b>最小货币单位</b>（CNY 分 / USD cent）的整数，禁止按「元/¥」解释或换算；符号与小数位由前端
 * {@code formatMoney(minor, currencyCode)} 按 {@code currencyCode} 渲染（16_CURRENCY_CONVENTIONS §4）。
 */
public record ShiftDto(Long id, Long tenantId, Long storeId, Long terminalId, Long operatorId,
                       LocalDateTime openedAt, LocalDateTime closedAt, BigDecimal openingCash,
                       BigDecimal expectedCash, BigDecimal actualCash, BigDecimal differenceAmount,
                       String currencyCode,
                       String status, Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
    public static ShiftDto from(ShiftPo po) {
        return new ShiftDto(po.getId(), po.getTenantId(), po.getStoreId(), po.getTerminalId(), po.getOperatorId(),
                po.getOpenedAt(), po.getClosedAt(), po.getOpeningCash(), po.getExpectedCash(), po.getActualCash(),
                po.getDifferenceAmount(), po.getCurrencyCode(),
                po.getStatus(), po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt());
    }
}
