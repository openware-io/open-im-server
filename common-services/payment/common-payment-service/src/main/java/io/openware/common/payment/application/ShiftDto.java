package io.openware.common.payment.application;

import io.openware.common.payment.domain.shift.model.Shift;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交班响应 DTO（由领域模型构造；含币种快照 {@code currencyCode}）。
 *
 * <p><b>金额单位（冻结）</b>：{@code openingCash}/{@code expectedCash}/{@code actualCash}/{@code differenceAmount}
 * 一律为<b>最小货币单位</b>（CNY 分 / USD cent）的整数，禁止按「元/¥」解释或换算；符号与小数位由前端
 * {@code formatMoney(minor, currencyCode)} 按 {@code currencyCode} 渲染（16_CURRENCY_CONVENTIONS §4）。
 *
 * <p>不暴露记账字段（createdBy、updatedBy）：它们是审计事实，由审计留痕承载
 * （standards/10 的 D2 规则）。{@code createdAt}/{@code updatedAt} 保留——交班记录时序对运营可读。
 */
public record ShiftDto(Long id, Long tenantId, Long storeId, Long terminalId, Long operatorId,
                       LocalDateTime openedAt, LocalDateTime closedAt, BigDecimal openingCash,
                       BigDecimal expectedCash, BigDecimal actualCash, BigDecimal differenceAmount,
                       String currencyCode,
                       String status, LocalDateTime createdAt, LocalDateTime updatedAt) {

    /** 由领域模型构造；入参为空时返回空。 */
    public static ShiftDto from(Shift shift) {
        if (shift == null) {
            return null;
        }
        return new ShiftDto(shift.id(), shift.tenantId(), shift.storeId(), shift.terminalId(),
                shift.operatorId(), shift.openedAt(), shift.closedAt(), shift.openingCash(),
                shift.expectedCash(), shift.actualCash(), shift.differenceAmount(), shift.currencyCode(),
                shift.status(), shift.createdAt(), shift.updatedAt());
    }
}
