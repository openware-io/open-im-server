package com.gvchat.common.payment.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("pay_daily_closing")
public class DailyClosingPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private LocalDate businessDate;
    private Long submittedBy;
    private Long reviewedBy;
    /** 币种快照（日结出具币种；跨币种必须分币种日结，不得合并）。 */
    private String currencyCode;
    /**
     * 日结汇总（按币种分组的 JSON，结构见 application.DailyClosingSummary）：收款笔数/金额、
     * 现金收取合计、退款合计、交班长短款合计，金额一律最小货币单位整数。
     * 历史行（本改动之前从不写入）为 {@code null}。
     */
    private String summaryJson;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
